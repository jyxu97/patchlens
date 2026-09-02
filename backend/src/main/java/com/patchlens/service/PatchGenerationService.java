package com.patchlens.service;

import com.patchlens.ai.PatchAiService;
import com.patchlens.ai.RepairAiService;
import com.patchlens.ai.dto.PatchProposal;
import com.patchlens.model.*;
import com.patchlens.repository.PatchSuggestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates unified diff patch suggestions for HIGH and MEDIUM severity findings.
 *
 * For each eligible finding:
 *   1. Calls PatchAiService (LangChain4j) or OpenAIService.generatePatch() (legacy mock)
 *   2. Validates the patch output (format, size, allowed files)
 *   3. Initiates repair loop (up to MAX_REPAIR_ATTEMPTS) using RepairAiService on failure
 *   4. Saves a PatchSuggestion entity
 */
@Service
public class PatchGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PatchGenerationService.class);

    /** Only generate patches for findings at or above this severity. */
    private static final Set<FindingSeverity> PATCH_ELIGIBLE_SEVERITIES =
            Set.of(FindingSeverity.HIGH, FindingSeverity.MEDIUM);

    /** Unified diff must not exceed this many lines. */
    private static final int MAX_PATCH_LINES = 200;

    /** Maximum repair iterations per finding (beyond the initial attempt). */
    private static final int MAX_REPAIR_ATTEMPTS = 2;

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

    /**
     * Generates patch suggestions for all eligible findings in the given list.
     *
     * @param findings     detected findings for this PR
     * @param changedFiles changed files from GitHub (used to find the matching diff)
     * @param contextChunks RAG context for the overall PR
     * @return persisted PatchSuggestion entities
     */
    public List<PatchSuggestion> generatePatches(List<ReviewFinding> findings,
                                                  List<ChangedFile> changedFiles,
                                                  List<String> contextChunks) {
        Set<String> allowedFiles = new HashSet<>();
        Map<String, ChangedFile> fileByName = new HashMap<>();
        for (ChangedFile cf : changedFiles) {
            allowedFiles.add(cf.filename());
            fileByName.put(cf.filename(), cf);
        }

        List<PatchSuggestion> result = new ArrayList<>();

        for (ReviewFinding finding : findings) {
            if (!PATCH_ELIGIBLE_SEVERITIES.contains(finding.getSeverity())) continue;

            ChangedFile targetFile = fileByName.get(finding.getFilePath());
            if (targetFile == null) {
                log.warn("Skipping patch for finding {} — file {} not in changed files",
                        finding.getId(), finding.getFilePath());
                continue;
            }

            PatchSuggestion patch = tryGenerate(finding, targetFile, contextChunks, allowedFiles);
            if (patch != null) {
                result.add(patch);
            }
        }

        return result;
    }

    /**
     * Generates a patch for one finding, validates it, and persists it.
     * Returns null if generation fails after all repair attempts.
     */
    private PatchSuggestion tryGenerate(ReviewFinding finding,
                                         ChangedFile targetFile,
                                         List<String> contextChunks,
                                         Set<String> allowedFiles) {
        // Step 1 — Generate initial patch
        OpenAIService.PatchOutput output = callGeneratePatch(finding, targetFile, contextChunks);
        PatchSuggestion patch = new PatchSuggestion(finding.getId(), output.unifiedDiff(), output.rationale());
        patch = patchSuggestionRepository.save(patch);

        String validationError = validatePatch(output.unifiedDiff(), finding.getFilePath(), allowedFiles);
        if (validationError == null) {
            patch.setStatus(ValidationStatus.VALIDATED);
            return patchSuggestionRepository.save(patch);
        }

        // Bounded repair loop
        for (int attempt = 1; attempt <= MAX_REPAIR_ATTEMPTS; attempt++) {
            log.info("Repair attempt {}/{} for finding {}: {}", attempt, MAX_REPAIR_ATTEMPTS,
                    finding.getId(), validationError);

            patch.setRepairAttempts(attempt);
            patch = patchSuggestionRepository.save(patch);

            OpenAIService.PatchOutput repaired = callRepairPatch(finding, patch, validationError);
            patch.setPatchText(repaired.unifiedDiff());
            patch.setRationale(repaired.rationale());
            patch = patchSuggestionRepository.save(patch);

            validationError = validatePatch(repaired.unifiedDiff(), finding.getFilePath(), allowedFiles);
            if (validationError == null) {
                patch.setStatus(ValidationStatus.VALIDATED);
                return patchSuggestionRepository.save(patch);
            }
        }

        patch.setStatus(ValidationStatus.REJECTED_POLICY);
        patchSuggestionRepository.save(patch);
        return null;
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
                    + ". Max " + MAX_PATCH_LINES + " lines.";

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
            // Truncate error logs fed back to the model
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

    /**
     * Validates a patch's format and constraints.
     *
     * @return null if valid; error message string if invalid
     */
    String validatePatch(String patchText, String expectedFilePath, Set<String> allowedFiles) {
        if (patchText == null || patchText.isBlank()) {
            return "Patch is empty";
        }

        // Must start with --- (unified diff format)
        String trimmed = patchText.stripLeading();
        if (!trimmed.startsWith("---")) {
            return "Patch does not start with '---' (invalid unified diff format)";
        }

        // Bounded size
        long lineCount = patchText.lines().count();
        if (lineCount > MAX_PATCH_LINES) {
            return "Patch exceeds " + MAX_PATCH_LINES + " lines (" + lineCount + " lines)";
        }

        // Must reference an allowed file
        boolean referencesAllowedFile = allowedFiles.stream()
                .anyMatch(f -> patchText.contains(f) || patchText.contains("a/" + f) || patchText.contains("b/" + f));
        if (!referencesAllowedFile) {
            return "Patch does not reference any allowed PR file (only PR files may be patched)";
        }

        return null;
    }
}
