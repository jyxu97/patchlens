package com.patchlens.service;

import com.patchlens.ai.ReviewAiService;
import com.patchlens.ai.dto.ReviewFindingDto;
import com.patchlens.model.*;
import com.patchlens.repository.ReviewFindingRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the multi-step issue detection pipeline:
 *   Step 1 — Diff Understanding (identify components + retrieval queries)
 *   Step 2 — Enhanced RAG retrieval with diff-derived queries
 *   Step 3 — Issue Detection:
 *       • ai.mode=openai → LangChain4j {@link ReviewAiService} with versioned prompts
 *       • ai.mode=mock   → legacy {@link OpenAIService} mock path
 *   Step 4 — Finding Filter (deterministic rules)
 *
 * Returns persisted {@link ReviewFinding} entities.
 */
@Service
public class IssueDetectionService {

    private final OpenAIService openAIService;
    private final Optional<ReviewAiService> reviewAiService;
    private final ContextRetrievalService contextRetrievalService;
    private final FindingFilterService findingFilterService;
    private final ReviewFindingRepository findingRepository;
    private final ObjectMapper objectMapper;
    private int maxFiles = 20;
    private int maxPatchChars = 4000;
    private int batchCharBudget = 32_000;
    private int maxBatches = 3;

    /** Review size limits (tokens and latency stay predictable). */
    @org.springframework.beans.factory.annotation.Autowired
    void configureLimits(@org.springframework.beans.factory.annotation.Value("${max.changed.files:20}") int maxFiles,
                         @org.springframework.beans.factory.annotation.Value("${max.patch.chars.per.file:4000}") int maxPatchChars,
                         @org.springframework.beans.factory.annotation.Value("${review.batch-char-budget:32000}") int batchCharBudget,
                         @org.springframework.beans.factory.annotation.Value("${review.max-batches:3}") int maxBatches) {
        this.maxFiles = maxFiles;
        this.maxPatchChars = maxPatchChars;
        this.batchCharBudget = batchCharBudget;
        this.maxBatches = maxBatches;
    }

    public IssueDetectionService(OpenAIService openAIService,
                                 Optional<ReviewAiService> reviewAiService,
                                 ContextRetrievalService contextRetrievalService,
                                 FindingFilterService findingFilterService,
                                 ReviewFindingRepository findingRepository,
                                 ObjectMapper objectMapper) {
        this.openAIService = openAIService;
        this.reviewAiService = reviewAiService;
        this.contextRetrievalService = contextRetrievalService;
        this.findingFilterService = findingFilterService;
        this.findingRepository = findingRepository;
        this.objectMapper = objectMapper;
    }

    /** Persisted findings plus the record of what was (and was not) reviewed. */
    public record Detection(List<ReviewFinding> findings, ReviewCoverage coverage) {}

    /** @see #detectWithCoverage */
    public List<ReviewFinding> detect(UUID reviewJobId, PullRequestMetadata metadata,
                                      List<ChangedFile> files, List<RiskScore> riskScores) {
        return detectWithCoverage(reviewJobId, metadata, files, riskScores).findings();
    }

    /**
     * Runs the multi-step issue detection pipeline for a PR and persists findings.
     *
     * <ol>
     *   <li>Plan: deterministic file selection, per-file diff truncation and batching
     *       ({@link ReviewPlanner}) — skipped/truncated files are recorded, not silently dropped</li>
     *   <li>Diff understanding (once, over the reviewed files)</li>
     *   <li>Context retrieval (active index version only)</li>
     *   <li>Issue detection — one model call per batch</li>
     *   <li>Deterministic filter: syntax + grounding against the diffs the model actually saw</li>
     * </ol>
     */
    public Detection detectWithCoverage(UUID reviewJobId,
                                        PullRequestMetadata metadata,
                                        List<ChangedFile> files,
                                        List<RiskScore> riskScores) {
        ReviewPlanner.Plan plan = ReviewPlanner.plan(files, riskScores, maxFiles, maxPatchChars, batchCharBudget, maxBatches);
        List<ChangedFile> reviewed = plan.reviewedFiles();
        if (reviewed.isEmpty()) return new Detection(List.of(), plan.coverage());

        // Step 1 — Diff Understanding (extracts components and retrieval queries)
        OpenAIService.DiffUnderstanding understanding = com.patchlens.observability.PatchLensMetrics.timeModel("diff_understanding",
                () -> openAIService.analyzeDiff(metadata, reviewed));

        // Step 2 — Enhanced Context Retrieval using diff-derived queries
        List<RetrievedContextChunk> retrieved = contextRetrievalService.retrieve(
                metadata, reviewed, riskScores, understanding.retrievalQueries());
        List<String> contextChunks = retrieved.stream()
                .map(RetrievedContextChunk::content)
                .toList();

        // Step 3 — Issue Detection, bounded batches
        List<OpenAIService.DetectedFinding> raw = new ArrayList<>();
        for (List<ChangedFile> batch : plan.batches()) {
            raw.addAll(com.patchlens.observability.PatchLensMetrics.timeModel("review", () -> reviewAiService.isPresent()
                    ? detectWithLangChain4j(metadata, batch, contextChunks, understanding)
                    : openAIService.detectFindings(metadata, batch, contextChunks, understanding)));
        }

        // Step 4 — Deterministic filter: a finding on a file the model never saw is ungrounded
        List<OpenAIService.DetectedFinding> filtered = findingFilterService.filter(raw, reviewed);

        // Persist findings
        List<ReviewFinding> saved = new ArrayList<>();
        for (OpenAIService.DetectedFinding df : filtered) {
            String evidenceJson = serializeEvidence(df.evidence());
            ReviewFinding finding = new ReviewFinding(
                    reviewJobId,
                    df.file(),
                    df.startLine(),
                    df.endLine(),
                    df.category(),
                    df.severity(),
                    df.title(),
                    df.explanation(),
                    df.confidence(),
                    evidenceJson
            );
            saved.add(findingRepository.save(finding));
        }

        return new Detection(saved, plan.coverage());
    }

    // =====================================================================
    // LangChain4j detection path
    // =====================================================================

    private List<OpenAIService.DetectedFinding> detectWithLangChain4j(
            PullRequestMetadata metadata,
            List<ChangedFile> files,
            List<String> contextChunks,
            OpenAIService.DiffUnderstanding understanding) {

        String prSummary = buildPrSummary(metadata, understanding);
        String diff      = buildDiff(files);
        String context   = buildContext(contextChunks);
        String constraints = buildConstraints(files);

        var response = reviewAiService.get().review(prSummary, diff, context, constraints);
        if (response == null || response.findings() == null) return List.of();

        return response.findings().stream()
                .map(this::toDetectedFinding)
                .toList();
    }

    /** Converts a LangChain4j DTO finding into the shared DetectedFinding record. */
    private OpenAIService.DetectedFinding toDetectedFinding(ReviewFindingDto dto) {
        List<OpenAIService.Evidence> evidence = dto.evidence() == null
                ? List.of()
                : dto.evidence().stream()
                      .map(ev -> new OpenAIService.Evidence(ev.file(), ev.line(), ev.snippet()))
                      .toList();
        return new OpenAIService.DetectedFinding(
                dto.file(),
                dto.startLine(),
                dto.endLine(),
                dto.category(),
                dto.severity(),
                dto.title(),
                dto.explanation(),
                evidence,
                dto.confidence()
        );
    }

    // =====================================================================
    // Prompt-content builders
    // =====================================================================

    private String buildPrSummary(PullRequestMetadata metadata, OpenAIService.DiffUnderstanding understanding) {
        return metadata.owner() + "/" + metadata.repo() + " #" + metadata.pullNumber()
                + " — " + metadata.title()
                + "\nComponents: " + String.join(", ", understanding.changedComponents())
                + "\nSymbols: " + String.join(", ", understanding.affectedSymbols());
    }

    private String buildDiff(List<ChangedFile> files) {
        StringBuilder sb = new StringBuilder();
        for (ChangedFile f : files) {
            if (f.patch() != null) {
                sb.append("--- ").append(f.filename()).append(" [").append(f.status()).append("]\n");
                String patch = f.patch().length() > 4000
                        ? f.patch().substring(0, 4000) + "\n...(truncated)"
                        : f.patch();
                sb.append(patch).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String buildContext(List<String> contextChunks) {
        if (contextChunks.isEmpty()) return "(no retrieved context)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contextChunks.size(); i++) {
            sb.append("--- chunk ").append(i + 1).append(" ---\n")
              .append(contextChunks.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    private String buildConstraints(List<ChangedFile> files) {
        String fileList = files.stream().map(ChangedFile::filename).collect(Collectors.joining(", "));
        return "Max 10 findings. Only report issues in these files: " + fileList;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private String serializeEvidence(List<OpenAIService.Evidence> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            return "[]";
        }
    }
}
