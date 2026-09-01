package com.patchlens.service;

import com.patchlens.model.*;
import com.patchlens.repository.ReviewFindingRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the multi-step issue detection pipeline:
 *   Step 1 — Diff Understanding (identify components + retrieval queries)
 *   Step 2 — Enhanced RAG retrieval with diff-derived queries
 *   Step 3 — Issue Detection (evidence-grounded findings from LLM)
 *   Step 4 — Finding Filter (deterministic rules)
 *
 * Returns persisted {@link ReviewFinding} entities.
 */
@Service
public class IssueDetectionService {

    private final OpenAIService openAIService;
    private final ContextRetrievalService contextRetrievalService;
    private final FindingFilterService findingFilterService;
    private final ReviewFindingRepository findingRepository;
    private final ObjectMapper objectMapper;

    public IssueDetectionService(OpenAIService openAIService,
                                 ContextRetrievalService contextRetrievalService,
                                 FindingFilterService findingFilterService,
                                 ReviewFindingRepository findingRepository,
                                 ObjectMapper objectMapper) {
        this.openAIService = openAIService;
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
        // Step 1 — Diff Understanding
        OpenAIService.DiffUnderstanding understanding = openAIService.analyzeDiff(metadata, files);

        // Step 2 — Enhanced Context Retrieval
        List<RetrievedContextChunk> retrieved = contextRetrievalService.retrieve(
                metadata, files, riskScores, understanding.retrievalQueries());
        List<String> contextChunks = retrieved.stream()
                .map(RetrievedContextChunk::content)
                .toList();

        // Step 3 — Issue Detection
        List<OpenAIService.DetectedFinding> raw = openAIService.detectFindings(
                metadata, files, contextChunks, understanding);

        // Step 4 — Filter
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

    private String serializeEvidence(List<OpenAIService.Evidence> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            return "[]";
        }
    }
}
