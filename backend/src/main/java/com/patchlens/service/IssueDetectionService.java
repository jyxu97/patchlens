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

    /**
     * Runs the full 4-step issue detection pipeline for a PR and persists findings.
     *
     * @param reviewJobId the owning job ID (stored in each finding)
     * @param metadata    PR metadata
     * @param files       changed files from GitHub API
     * @param riskScores  rule-based risk scores (used for retrieval)
     * @return list of persisted ReviewFinding entities
     */
    public List<ReviewFinding> detect(UUID reviewJobId,
                                      PullRequestMetadata metadata,
                                      List<ChangedFile> files,
                                      List<RiskScore> riskScores) {
        // Step 1 — Diff Understanding (extracts components and retrieval queries)
        OpenAIService.DiffUnderstanding understanding = openAIService.analyzeDiff(metadata, files);

        // Step 2 — Enhanced Context Retrieval using diff-derived queries
        List<RetrievedContextChunk> retrieved = contextRetrievalService.retrieve(
                metadata, files, riskScores, understanding.retrievalQueries());
        List<String> contextChunks = retrieved.stream()
                .map(RetrievedContextChunk::content)
                .toList();

        // Step 3 — Issue Detection
        List<OpenAIService.DetectedFinding> raw = reviewAiService.isPresent()
                ? detectWithLangChain4j(metadata, files, contextChunks, understanding)
                : openAIService.detectFindings(metadata, files, contextChunks, understanding);

        // Step 4 — Deterministic filter (removes malformed, out-of-range, low-confidence findings)
        List<OpenAIService.DetectedFinding> filtered = findingFilterService.filter(raw, files);

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

        return saved;
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
