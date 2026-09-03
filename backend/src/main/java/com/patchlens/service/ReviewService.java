package com.patchlens.service;

import com.patchlens.dto.GroundingReport;
import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.*;
import com.patchlens.repository.AnalysisRunRepository;
import com.patchlens.repository.ReviewFindingRepository;
import com.patchlens.repository.ReviewSessionRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Core analysis pipeline, usable by both the synchronous HTTP endpoint
 * (ReviewController) and the async RabbitMQ worker (ReviewJobWorker).
 */
@Service
public class ReviewService {

    private final GitHubService gitHubService;
    private final DiffParserService diffParserService;
    private final RiskScoringService riskScoringService;
    private final OpenAIService openAIService;
    private final CacheService cacheService;
    private final ContextIndexingService contextIndexingService;
    private final ContextRetrievalService contextRetrievalService;
    private final GroundingValidationService groundingValidationService;
    private final PromptVersionService promptVersionService;
    private final ReviewSessionRepository sessionRepository;
    private final AnalysisRunRepository analysisRunRepository;
    private final ReviewFindingRepository findingRepository;
    private final IssueDetectionService issueDetectionService;
    private final ObjectMapper objectMapper;

    public ReviewService(GitHubService gitHubService,
                         DiffParserService diffParserService,
                         RiskScoringService riskScoringService,
                         OpenAIService openAIService,
                         CacheService cacheService,
                         ContextIndexingService contextIndexingService,
                         ContextRetrievalService contextRetrievalService,
                         GroundingValidationService groundingValidationService,
                         PromptVersionService promptVersionService,
                         ReviewSessionRepository sessionRepository,
                         AnalysisRunRepository analysisRunRepository,
                         ReviewFindingRepository findingRepository,
                         IssueDetectionService issueDetectionService,
                         ObjectMapper objectMapper) {
        this.gitHubService = gitHubService;
        this.diffParserService = diffParserService;
        this.riskScoringService = riskScoringService;
        this.openAIService = openAIService;
        this.cacheService = cacheService;
        this.contextIndexingService = contextIndexingService;
        this.contextRetrievalService = contextRetrievalService;
        this.groundingValidationService = groundingValidationService;
        this.promptVersionService = promptVersionService;
        this.sessionRepository = sessionRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.findingRepository = findingRepository;
        this.issueDetectionService = issueDetectionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Result returned by runAnalysisV2 — extends AnalysisOutcome with multi-step findings
     * and the raw PR data needed for patch generation and review comment posting.
     */
    public record AnalysisOutcomeV2(
            AnalysisOutcome base,
            List<ReviewFinding> findings,
            List<ChangedFile> changedFiles,
            List<String> contextChunks
    ) {}

    /**
     * Result returned by runAnalysis — carries all fields needed by
     * ReviewController (HTTP response) and ReviewJobWorker (job update).
     */
    public record AnalysisOutcome(
            String owner,
            String repo,
            int pullNumber,
            String title,
            String diffHash,
            boolean cacheHit,
            boolean indexing,
            RiskScore.RiskLevel overallRisk,
            List<RiskScore> riskScores,
            ReviewResult reviewResult,
            List<RetrievedContextChunk> retrievedContext,
            UUID reviewSessionId,
            GroundingReport groundingReport
    ) {}

    /**
     * Runs the full GitHub PR analysis pipeline:
     * fetch → diff hash → cache check → risk scoring → RAG retrieval → AI review → persist.
     *
     * @throws GitHubApiException if the GitHub API call fails
     */
    public AnalysisOutcome runAnalysis(String owner, String repo, int pullNumber, String prUrl)
            throws GitHubApiException {

        long totalStart = System.currentTimeMillis();

        long githubStart = System.currentTimeMillis();
        PullRequestMetadata metadata = gitHubService.fetchMetadata(owner, repo, pullNumber);
        List<ChangedFile> files = gitHubService.fetchChangedFiles(owner, repo, pullNumber);
        long githubMs = System.currentTimeMillis() - githubStart;

        String diffHash = diffParserService.hash(diffParserService.normalize(metadata, files));
        String cacheKey = cacheService.reviewKey(owner, repo, pullNumber, diffHash);

        Optional<CachedAnalysis> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            long totalMs = System.currentTimeMillis() - totalStart;
            analysisRunRepository.save(new AnalysisRun(
                    prUrl, null, diffHash,
                    true, githubMs, 0, 0, totalMs, 0, 0, "cached", "success", null, null, null, null
            ));
            CachedAnalysis ca = cached.get();
            return new AnalysisOutcome(
                    owner, repo, pullNumber, metadata.title(), diffHash,
                    true, false,
                    ca.overallRisk(), ca.riskScores(),
                    ca.reviewResult(), ca.retrievedContext(),
                    null, null
            );
        }

        // Cache miss: run full pipeline
        List<RiskScore> riskScores = riskScoringService.score(files);
        RiskScore.RiskLevel overallRisk = riskScoringService.overallRisk(riskScores);

        boolean alreadyIndexed = contextIndexingService.isIndexed(owner, repo);
        if (!contextIndexingService.isUpToDate(owner, repo)) {
            contextIndexingService.autoIndex(owner, repo);
        }
        boolean indexing = !alreadyIndexed;

        long retrievalStart = System.currentTimeMillis();
        List<RetrievedContextChunk> retrieved = contextRetrievalService.retrieve(metadata, files, riskScores);
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        List<String> contextChunks = retrieved.stream()
                .map(RetrievedContextChunk::content)
                .toList();

        long llmStart = System.currentTimeMillis();
        OpenAIService.GenerateReviewResult generated =
                openAIService.generateReview(metadata, files, riskScores, contextChunks);
        long llmMs = System.currentTimeMillis() - llmStart;

        GroundingReport groundingReport = groundingValidationService.validate(generated.reviewResult(), files);

        long totalMs = System.currentTimeMillis() - totalStart;

        cacheService.put(cacheKey, new CachedAnalysis(
                generated.reviewResult(), overallRisk, riskScores, retrieved
        ), cacheService.ttlForGitHubPr());

        ReviewSession session = new ReviewSession(
                owner, repo, pullNumber, metadata.url(),
                diffHash, cacheKey, "github", toJson(generated.reviewResult())
        );
        sessionRepository.save(session);

        UUID promptVersionId = promptVersionService.getCurrentVersion() != null
                ? promptVersionService.getCurrentVersion().getId() : null;

        analysisRunRepository.save(new AnalysisRun(
                prUrl, null, diffHash,
                false, githubMs, retrievalMs, llmMs, totalMs,
                generated.promptTokens(), generated.completionTokens(),
                generated.modelName(), "success", null,
                groundingReport.hallucinatedCount(), groundingReport.groundingRate(),
                promptVersionId
        ));

        return new AnalysisOutcome(
                owner, repo, pullNumber, metadata.title(), diffHash,
                false, indexing,
                overallRisk, riskScores,
                generated.reviewResult(), retrieved,
                session.getId(), groundingReport
        );
    }

    /**
     * Runs the sample PR analysis pipeline (no GitHub API calls).
     */
    public AnalysisOutcome runSampleAnalysis(String sampleId, SamplePrLoader.SamplePr sample) {
        long totalStart = System.currentTimeMillis();

        String diffHash = diffParserService.hash(diffParserService.normalize(sample.metadata(), sample.files()));
        String cacheKey = cacheService.sampleKey(sampleId, diffHash);

        Optional<CachedAnalysis> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            long totalMs = System.currentTimeMillis() - totalStart;
            analysisRunRepository.save(new AnalysisRun(
                    null, sampleId, diffHash,
                    true, 0, 0, 0, totalMs, 0, 0, "cached", "success", null, null, null, null
            ));
            CachedAnalysis ca = cached.get();
            return new AnalysisOutcome(
                    sample.metadata().owner(), sample.metadata().repo(),
                    sample.metadata().pullNumber(), sample.metadata().title(), diffHash,
                    true, false,
                    ca.overallRisk(), ca.riskScores(),
                    ca.reviewResult(), ca.retrievedContext(),
                    null, null
            );
        }

        List<RiskScore> riskScores = riskScoringService.score(sample.files());
        RiskScore.RiskLevel overallRisk = riskScoringService.overallRisk(riskScores);

        long retrievalStart = System.currentTimeMillis();
        List<RetrievedContextChunk> retrieved =
                contextRetrievalService.retrieve(sample.metadata(), sample.files(), riskScores);
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        List<String> contextChunks = retrieved.stream()
                .map(RetrievedContextChunk::content)
                .toList();

        long llmStart = System.currentTimeMillis();
        OpenAIService.GenerateReviewResult generated =
                openAIService.generateReview(sample.metadata(), sample.files(), riskScores, contextChunks);
        long llmMs = System.currentTimeMillis() - llmStart;

        GroundingReport groundingReport = groundingValidationService.validate(generated.reviewResult(), sample.files());

        long totalMs = System.currentTimeMillis() - totalStart;

        cacheService.put(cacheKey, new CachedAnalysis(
                generated.reviewResult(), overallRisk, riskScores, retrieved
        ), cacheService.ttlForSamplePr());

        ReviewSession session = new ReviewSession(
                sample.metadata().owner(), sample.metadata().repo(),
                sample.metadata().pullNumber(), sample.metadata().url(),
                diffHash, cacheKey, "sample", toJson(generated.reviewResult())
        );
        sessionRepository.save(session);

        UUID promptVersionId = promptVersionService.getCurrentVersion() != null
                ? promptVersionService.getCurrentVersion().getId() : null;

        analysisRunRepository.save(new AnalysisRun(
                null, sampleId, diffHash,
                false, 0, retrievalMs, llmMs, totalMs,
                generated.promptTokens(), generated.completionTokens(),
                generated.modelName(), "success", null,
                groundingReport.hallucinatedCount(), groundingReport.groundingRate(),
                promptVersionId
        ));

        return new AnalysisOutcome(
                sample.metadata().owner(), sample.metadata().repo(),
                sample.metadata().pullNumber(), sample.metadata().title(), diffHash,
                false, false,
                overallRisk, riskScores,
                generated.reviewResult(), retrieved,
                session.getId(), groundingReport
        );
    }

    /**
     * V2 pipeline: runs the standard analysis THEN the multi-step issue detection.
     * Used by ReviewJobWorker for webhook-triggered jobs.
     * runAnalysis() is preserved for sample PRs and the HTTP endpoint.
     *
     * @param jobId the ReviewJob id — stored in each persisted finding
     * @throws GitHubApiException if the GitHub API call fails
     */
    public AnalysisOutcomeV2 runAnalysisV2(UUID jobId, String owner, String repo,
                                            int pullNumber, String prUrl)
            throws GitHubApiException {

        // Run the existing single-shot pipeline for backward compat (summary/checklist)
        AnalysisOutcome base = runAnalysis(owner, repo, pullNumber, prUrl);

        // Run multi-step issue detection on the same PR data
        PullRequestMetadata metadata = gitHubService.fetchMetadata(owner, repo, pullNumber);
        List<ChangedFile> files = gitHubService.fetchChangedFiles(owner, repo, pullNumber);
        List<RiskScore> riskScores = riskScoringService.score(files);

        List<ReviewFinding> findings = issueDetectionService.detect(jobId, metadata, files, riskScores);

        List<String> contextChunks = base.retrievedContext().stream()
                .map(RetrievedContextChunk::content)
                .toList();

        return new AnalysisOutcomeV2(base, findings, files, contextChunks);
    }

    public String toJson(ReviewResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ReviewResult", e);
        }
    }

    public ReviewResult fromJson(String json) {
        try {
            return objectMapper.readValue(json, ReviewResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ReviewResult", e);
        }
    }
}
