package com.patchlens.service;

import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.*;
import com.patchlens.repository.AnalysisRunRepository;
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
    private final ReviewSessionRepository sessionRepository;
    private final AnalysisRunRepository analysisRunRepository;
    private final ObjectMapper objectMapper;

    public ReviewService(GitHubService gitHubService,
                         DiffParserService diffParserService,
                         RiskScoringService riskScoringService,
                         OpenAIService openAIService,
                         CacheService cacheService,
                         ContextIndexingService contextIndexingService,
                         ContextRetrievalService contextRetrievalService,
                         ReviewSessionRepository sessionRepository,
                         AnalysisRunRepository analysisRunRepository,
                         ObjectMapper objectMapper) {
        this.gitHubService = gitHubService;
        this.diffParserService = diffParserService;
        this.riskScoringService = riskScoringService;
        this.openAIService = openAIService;
        this.cacheService = cacheService;
        this.contextIndexingService = contextIndexingService;
        this.contextRetrievalService = contextRetrievalService;
        this.sessionRepository = sessionRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.objectMapper = objectMapper;
    }

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
            UUID reviewSessionId
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
                    true, githubMs, 0, 0, totalMs, 0, 0, "cached", "success", null
            ));
            CachedAnalysis ca = cached.get();
            return new AnalysisOutcome(
                    owner, repo, pullNumber, metadata.title(), diffHash,
                    true, false,
                    ca.overallRisk(), ca.riskScores(),
                    ca.reviewResult(), ca.retrievedContext(),
                    null
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

        long totalMs = System.currentTimeMillis() - totalStart;

        cacheService.put(cacheKey, new CachedAnalysis(
                generated.reviewResult(), overallRisk, riskScores, retrieved
        ), cacheService.ttlForGitHubPr());

        ReviewSession session = new ReviewSession(
                owner, repo, pullNumber, metadata.url(),
                diffHash, cacheKey, "github", toJson(generated.reviewResult())
        );
        sessionRepository.save(session);

        analysisRunRepository.save(new AnalysisRun(
                prUrl, null, diffHash,
                false, githubMs, retrievalMs, llmMs, totalMs,
                generated.promptTokens(), generated.completionTokens(),
                generated.modelName(), "success", null
        ));

        return new AnalysisOutcome(
                owner, repo, pullNumber, metadata.title(), diffHash,
                false, indexing,
                overallRisk, riskScores,
                generated.reviewResult(), retrieved,
                session.getId()
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
                    true, 0, 0, 0, totalMs, 0, 0, "cached", "success", null
            ));
            CachedAnalysis ca = cached.get();
            return new AnalysisOutcome(
                    sample.metadata().owner(), sample.metadata().repo(),
                    sample.metadata().pullNumber(), sample.metadata().title(), diffHash,
                    true, false,
                    ca.overallRisk(), ca.riskScores(),
                    ca.reviewResult(), ca.retrievedContext(),
                    null
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

        analysisRunRepository.save(new AnalysisRun(
                null, sampleId, diffHash,
                false, 0, retrievalMs, llmMs, totalMs,
                generated.promptTokens(), generated.completionTokens(),
                generated.modelName(), "success", null
        ));

        return new AnalysisOutcome(
                sample.metadata().owner(), sample.metadata().repo(),
                sample.metadata().pullNumber(), sample.metadata().title(), diffHash,
                false, false,
                overallRisk, riskScores,
                generated.reviewResult(), retrieved,
                session.getId()
        );
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
