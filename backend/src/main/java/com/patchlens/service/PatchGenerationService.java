package com.patchlens.service;

import com.patchlens.ai.PatchAiService;
import com.patchlens.ai.RepairAiService;
import com.patchlens.ai.dto.PatchProposal;
import com.patchlens.model.*;
import com.patchlens.repository.PatchSuggestionRepository;
import com.patchlens.sandbox.PatchPolicy;
import com.patchlens.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Model-facing half of the patch pipeline: generates candidate unified diffs for eligible
 * findings and produces repaired candidates from validation feedback.
 *
 * <p>This class never decides whether a patch is valid. Every candidate is persisted as
 * {@link ValidationStatus#PENDING}; {@link PatchPipelineService} runs policy and sandbox
 * validation and owns the bounded repair loop.
 */
@Service
public class PatchGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PatchGenerationService.class);

    /** Only generate patches for findings at or above this severity. */
    static final Set<FindingSeverity> PATCH_ELIGIBLE_SEVERITIES =
            Set.of(FindingSeverity.HIGH, FindingSeverity.MEDIUM);

    /** Maximum repair iterations per finding (beyond the initial attempt). */
    public static final int MAX_REPAIR_ATTEMPTS = 2;

    private final OpenAIService openAIService;
    private final Optional<PatchAiService> patchAiService;
    private final Optional<RepairAiService> repairAiService;
    private final PatchSuggestionRepository patchSuggestionRepository;

    public PatchGenerationService(OpenAIService openAIService,
                                  Optional<PatchAiService> patchAiService,
                                  Optional<RepairAiService> repairAiService,
                                  PatchSuggestionRepository patchSuggestionRepository) {
        this.openAIService = openAIService;
        this.patchAiService = patchAiService;
        this.repairAiService = repairAiService;
        this.patchSuggestionRepository = patchSuggestionRepository;
    }

    public static boolean isEligible(ReviewFinding finding) {
        return PATCH_ELIGIBLE_SEVERITIES.contains(finding.getSeverity());
    }

    /** A finding paired with its initial candidate patch (status PENDING). */
    public record Candidate(ReviewFinding finding, PatchSuggestion patch) {}

    /**
     * Generates one initial candidate per eligible (HIGH/MEDIUM) finding whose file is part of
     * the PR. A model failure for one finding is logged and skipped; it does not fail the job.
     */
    public List<Candidate> generateCandidates(List<ReviewFinding> findings,
                                              List<ChangedFile> changedFiles,
                                              List<String> contextChunks) {
        Map<String, ChangedFile> fileByName = new HashMap<>();
        for (ChangedFile cf : changedFiles) fileByName.put(cf.filename(), cf);

        List<Candidate> result = new ArrayList<>();
        for (ReviewFinding finding : findings) {
            if (!isEligible(finding)) continue;
            ChangedFile targetFile = fileByName.get(finding.getFilePath());
            if (targetFile == null) {
                log.warn("Skipping patch for finding {} — file {} not in changed files",
                        finding.getId(), finding.getFilePath());
                continue;
            }
            generateCandidate(finding, targetFile, contextChunks)
                    .ifPresent(p -> result.add(new Candidate(finding, p)));
        }
        return result;
    }

    /** Generates and persists the initial candidate for one finding (status PENDING). */
    public Optional<PatchSuggestion> generateCandidate(ReviewFinding finding,
                                                       ChangedFile targetFile,
                                                       List<String> contextChunks) {
        try {
            OpenAIService.PatchOutput output = callGeneratePatch(finding, targetFile, contextChunks);
            String text = output.unifiedDiff() == null ? "" : output.unifiedDiff();
            PatchSuggestion patch = new PatchSuggestion(finding.getId(), text, output.rationale());
            return Optional.of(patchSuggestionRepository.save(patch));
        } catch (RuntimeException e) {
            log.warn("Patch generation failed for finding {}: {}", finding.getId(), Redactor.redact(e.getMessage()));
            return Optional.empty();
        }
    }

    /**
     * Asks the repair model for a replacement patch given the previous attempt's failure.
     * Increments {@code repairAttempts} and resets the status to PENDING (the new text has not
     * been validated yet).
     *
     * @throws IllegalStateException if the repair budget is already exhausted
     */
    public PatchSuggestion repair(ReviewFinding finding, PatchSuggestion previous, String failureFeedback) {
        if (previous.getRepairAttempts() >= MAX_REPAIR_ATTEMPTS) {
            throw new IllegalStateException("Repair budget exhausted for patch " + previous.getId());
        }
        OpenAIService.PatchOutput repaired = callRepairPatch(finding, previous, failureFeedback);
        previous.setPatchText(repaired.unifiedDiff() == null ? "" : repaired.unifiedDiff());
        previous.setRationale(repaired.rationale());
        previous.setRepairAttempts(previous.getRepairAttempts() + 1);
        previous.setStatus(ValidationStatus.PENDING);
        return patchSuggestionRepository.save(previous);
    }

    // =====================================================================
    // LangChain4j / legacy dispatch
    // =====================================================================

    private OpenAIService.PatchOutput callGeneratePatch(ReviewFinding finding,
                                                         ChangedFile targetFile,
                                                         List<String> contextChunks) {
        if (patchAiService.isPresent()) {
            String findingDesc = buildFindingDescription(finding);
            String targetCode  = targetFile.patch() != null
                    ? truncate(targetFile.patch(), 4000) : "(no diff)";
            String context     = String.join("\n\n", contextChunks);
            String constraints = "Allowed file: " + finding.getFilePath()
                    + ". Max " + PatchPolicy.MAX_PATCH_LINES + " lines. Use a/ and b/ path prefixes.";

            PatchProposal proposal = patchAiService.get()
                    .generatePatch(findingDesc, targetCode, context, constraints);
            return new OpenAIService.PatchOutput(
                    proposal.unifiedDiff(), proposal.rationale(), proposal.expectedBehavior());
        }
        return openAIService.generatePatch(finding, targetFile, contextChunks);
    }

    private OpenAIService.PatchOutput callRepairPatch(ReviewFinding finding,
                                                       PatchSuggestion previousPatch,
                                                       String validationError) {
        if (repairAiService.isPresent()) {
            String findingDesc    = buildFindingDescription(finding);
            String previousPatchText = previousPatch.getPatchText();
            String truncatedError = truncate(validationError, 2000);

            PatchProposal proposal = repairAiService.get()
                    .repair(findingDesc, previousPatchText, truncatedError, "");
            return new OpenAIService.PatchOutput(
                    proposal.unifiedDiff(), proposal.rationale(), proposal.expectedBehavior());
        }
        return openAIService.repairPatch(finding, previousPatch, validationError);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private String buildFindingDescription(ReviewFinding finding) {
        return "File: " + finding.getFilePath()
                + "\nLines: " + finding.getLineStart() + "-" + finding.getLineEnd()
                + "\nSeverity: " + finding.getSeverity()
                + "\nCategory: " + finding.getCategory()
                + "\nTitle: " + finding.getTitle()
                + "\nExplanation: " + finding.getExplanation();
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() > maxChars ? text.substring(0, maxChars) + "\n...(truncated)" : text;
    }
}
