package com.patchlens.controller;

import com.patchlens.dto.AnalyzePullRequestRequest;
import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.*;
import com.patchlens.repository.AnalysisRunRepository;
import com.patchlens.repository.ReviewSessionRepository;
import com.patchlens.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final SamplePrLoader samplePrLoader;
    private final GitHubPrUrlParser urlParser;
    private final GitHubService gitHubService;
    private final DiffParserService diffParserService;
    private final RiskScoringService riskScoringService;
    private final OpenAIService openAIService;
    private final CacheService cacheService;
    private final ContextRetrievalService contextRetrievalService;
    private final ReviewSessionRepository sessionRepository;
    private final AnalysisRunRepository analysisRunRepository;
    private final ObjectMapper objectMapper;

    public ReviewController(SamplePrLoader samplePrLoader,
                            GitHubPrUrlParser urlParser,
                            GitHubService gitHubService,
                            DiffParserService diffParserService,
                            RiskScoringService riskScoringService,
                            OpenAIService openAIService,
                            CacheService cacheService,
                            ContextRetrievalService contextRetrievalService,
                            ReviewSessionRepository sessionRepository,
                            AnalysisRunRepository analysisRunRepository,
                            ObjectMapper objectMapper) {
        this.samplePrLoader = samplePrLoader;
        this.urlParser = urlParser;
        this.gitHubService = gitHubService;
        this.diffParserService = diffParserService;
        this.riskScoringService = riskScoringService;
        this.openAIService = openAIService;
        this.cacheService = cacheService;
        this.contextRetrievalService = contextRetrievalService;
        this.sessionRepository = sessionRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Analyzes a real GitHub PR.
     * POST /api/reviews/analyze
     * Body: { "pullRequestUrl": "https://github.com/owner/repo/pull/123" }
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@Valid @RequestBody AnalyzePullRequestRequest request) {
        long totalStart = System.currentTimeMillis();

        var parsed = urlParser.parse(request.pullRequestUrl());
        if (parsed.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "INVALID_PR_URL",
                    "message", "Please enter a valid GitHub pull request URL."
            ));
        }
        var pr = parsed.get();

        PullRequestMetadata metadata;
        List<ChangedFile> files;
        try {
            metadata = gitHubService.fetchMetadata(pr.owner(), pr.repo(), pr.pullNumber());
            files = gitHubService.fetchChangedFiles(pr.owner(), pr.repo(), pr.pullNumber());
        } catch (GitHubApiException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getErrorCode(),
                    "message", e.getMessage()
            ));
        }

        String diffHash = diffParserService.hash(diffParserService.normalize(metadata, files));
        String cacheKey = cacheService.reviewKey(pr.owner(), pr.repo(), pr.pullNumber(), diffHash);

        // Check cache first — if the diff hasn't changed, skip AI entirely
        Optional<ReviewResult> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            long totalMs = System.currentTimeMillis() - totalStart;
            analysisRunRepository.save(new AnalysisRun(
                    request.pullRequestUrl(), null, diffHash,
                    true, 0, 0, totalMs, 0, 0, "cached", "success", null
            ));
            return ResponseEntity.ok(Map.of(
                    "repository", pr.owner() + "/" + pr.repo(),
                    "pullRequestNumber", pr.pullNumber(),
                    "title", metadata.title(),
                    "diffHash", diffHash,
                    "cacheHit", true,
                    "result", cached.get()
            ));
        }

        // Cache miss: run full pipeline
        List<RiskScore> riskScores = riskScoringService.score(files);
        RiskScore.RiskLevel overallRisk = riskScoringService.overallRisk(riskScores);

        // Retrieve top-k repository context chunks from pgvector for RAG
        long retrievalStart = System.currentTimeMillis();
        List<String> contextChunks = contextRetrievalService
                .retrieve(metadata, files, riskScores)
                .stream()
                .map(RepositoryContextChunk::getContent)
                .toList();
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        long llmStart = System.currentTimeMillis();
        OpenAIService.GenerateReviewResult generated =
                openAIService.generateReview(metadata, files, riskScores, contextChunks);
        long llmMs = System.currentTimeMillis() - llmStart;

        long totalMs = System.currentTimeMillis() - totalStart;

        cacheService.put(cacheKey, generated.reviewResult(), cacheService.ttlForGitHubPr());

        // Persist the session and analysis run to PostgreSQL
        ReviewSession session = new ReviewSession(
                pr.owner(), pr.repo(), pr.pullNumber(), metadata.url(),
                diffHash, cacheKey, "github", toJson(generated.reviewResult())
        );
        sessionRepository.save(session);

        analysisRunRepository.save(new AnalysisRun(
                request.pullRequestUrl(), null, diffHash,
                false, retrievalMs, llmMs, totalMs,
                generated.promptTokens(), generated.completionTokens(),
                generated.modelName(), "success", null
        ));

        return ResponseEntity.ok(Map.of(
                "reviewSessionId", session.getId(),
                "repository", pr.owner() + "/" + pr.repo(),
                "pullRequestNumber", pr.pullNumber(),
                "title", metadata.title(),
                "diffHash", diffHash,
                "cacheHit", false,
                "overallRisk", overallRisk,
                "riskScores", riskScores,
                "result", generated.reviewResult()
        ));
    }

    /**
     * Analyzes a sample PR by its fixture ID.
     * POST /api/reviews/analyze-sample
     * Body: { "sampleId": "redis-session-cache" }
     */
    @PostMapping("/analyze-sample")
    public ResponseEntity<?> analyzeSample(@Valid @RequestBody AnalyzeSampleRequest request) {
        long totalStart = System.currentTimeMillis();

        SamplePrLoader.SamplePr sample;
        try {
            sample = samplePrLoader.load(request.sampleId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "SAMPLE_NOT_FOUND",
                    "message", "Unknown sample ID: " + request.sampleId()
            ));
        }

        String diffHash = diffParserService.hash(diffParserService.normalize(sample.metadata(), sample.files()));
        String cacheKey = cacheService.sampleKey(request.sampleId(), diffHash);

        // Check cache first
        Optional<ReviewResult> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            long totalMs = System.currentTimeMillis() - totalStart;
            analysisRunRepository.save(new AnalysisRun(
                    null, request.sampleId(), diffHash,
                    true, 0, 0, totalMs, 0, 0, "cached", "success", null
            ));
            return ResponseEntity.ok(Map.of(
                    "sampleId", request.sampleId(),
                    "repository", sample.metadata().owner() + "/" + sample.metadata().repo(),
                    "pullRequestNumber", sample.metadata().pullNumber(),
                    "title", sample.metadata().title(),
                    "diffHash", diffHash,
                    "cacheHit", true,
                    "result", cached.get()
            ));
        }

        // Cache miss: run full pipeline
        List<RiskScore> riskScores = riskScoringService.score(sample.files());
        RiskScore.RiskLevel overallRisk = riskScoringService.overallRisk(riskScores);

        // Retrieve top-k repository context chunks from pgvector for RAG
        long retrievalStart = System.currentTimeMillis();
        List<String> contextChunks = contextRetrievalService
                .retrieve(sample.metadata(), sample.files(), riskScores)
                .stream()
                .map(RepositoryContextChunk::getContent)
                .toList();
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        long llmStart = System.currentTimeMillis();
        OpenAIService.GenerateReviewResult generated =
                openAIService.generateReview(sample.metadata(), sample.files(), riskScores, contextChunks);
        long llmMs = System.currentTimeMillis() - llmStart;

        long totalMs = System.currentTimeMillis() - totalStart;

        cacheService.put(cacheKey, generated.reviewResult(), cacheService.ttlForSamplePr());

        // Persist the session and analysis run to PostgreSQL
        ReviewSession session = new ReviewSession(
                sample.metadata().owner(), sample.metadata().repo(),
                sample.metadata().pullNumber(), sample.metadata().url(),
                diffHash, cacheKey, "sample", toJson(generated.reviewResult())
        );
        sessionRepository.save(session);

        analysisRunRepository.save(new AnalysisRun(
                null, request.sampleId(), diffHash,
                false, retrievalMs, llmMs, totalMs,
                generated.promptTokens(), generated.completionTokens(),
                generated.modelName(), "success", null
        ));

        return ResponseEntity.ok(Map.of(
                "reviewSessionId", session.getId(),
                "sampleId", request.sampleId(),
                "repository", sample.metadata().owner() + "/" + sample.metadata().repo(),
                "pullRequestNumber", sample.metadata().pullNumber(),
                "title", sample.metadata().title(),
                "diffHash", diffHash,
                "cacheHit", false,
                "overallRisk", overallRisk,
                "riskScores", riskScores,
                "result", generated.reviewResult()
        ));
    }

    /**
     * Fetches a previously saved review by its session ID.
     * GET /api/reviews/{reviewSessionId}
     */
    @GetMapping("/{reviewSessionId}")
    public ResponseEntity<?> getReview(@PathVariable UUID reviewSessionId) {
        return sessionRepository.findById(reviewSessionId)
                .map(session -> {
                    ReviewResult result = fromJson(session.getResultJson());
                    return ResponseEntity.ok(Map.of(
                            "reviewSessionId", session.getId(),
                            "repository", session.getRepositoryOwner() + "/" + session.getRepositoryName(),
                            "pullRequestNumber", session.getPullRequestNumber(),
                            "diffHash", session.getDiffHash(),
                            "mode", session.getMode(),
                            "createdAt", session.getCreatedAt(),
                            "result", result
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // --- helpers ---

    private String toJson(ReviewResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ReviewResult", e);
        }
    }

    private ReviewResult fromJson(String json) {
        try {
            return objectMapper.readValue(json, ReviewResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ReviewResult", e);
        }
    }

    // Request body for analyze-sample endpoint only; kept here to avoid a separate file
    record AnalyzeSampleRequest(@NotBlank String sampleId) {}
}
