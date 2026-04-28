package com.patchlens.controller;

import com.patchlens.dto.AnalyzePullRequestRequest;
import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.ChangedFile;
import com.patchlens.model.PullRequestMetadata;
import com.patchlens.model.ReviewResult;
import com.patchlens.model.RiskScore;
import com.patchlens.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public ReviewController(SamplePrLoader samplePrLoader,
                            GitHubPrUrlParser urlParser,
                            GitHubService gitHubService,
                            DiffParserService diffParserService,
                            RiskScoringService riskScoringService,
                            OpenAIService openAIService,
                            CacheService cacheService) {
        this.samplePrLoader = samplePrLoader;
        this.urlParser = urlParser;
        this.gitHubService = gitHubService;
        this.diffParserService = diffParserService;
        this.riskScoringService = riskScoringService;
        this.openAIService = openAIService;
        this.cacheService = cacheService;
    }

    /**
     * Analyzes a real GitHub PR.
     * POST /api/reviews/analyze
     * Body: { "pullRequestUrl": "https://github.com/owner/repo/pull/123" }
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@Valid @RequestBody AnalyzePullRequestRequest request) {

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
        ReviewResult reviewResult = openAIService.generateReview(metadata, files, riskScores);

        cacheService.put(cacheKey, reviewResult, cacheService.ttlForGitHubPr());

        return ResponseEntity.ok(Map.of(
                "repository", pr.owner() + "/" + pr.repo(),
                "pullRequestNumber", pr.pullNumber(),
                "title", metadata.title(),
                "diffHash", diffHash,
                "cacheHit", false,
                "overallRisk", overallRisk,
                "riskScores", riskScores,
                "result", reviewResult
        ));
    }

    /**
     * Analyzes a sample PR by its fixture ID.
     * POST /api/reviews/analyze-sample
     * Body: { "sampleId": "redis-session-cache" }
     */
    @PostMapping("/analyze-sample")
    public ResponseEntity<?> analyzeSample(@Valid @RequestBody AnalyzeSampleRequest request) {

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
        ReviewResult reviewResult = openAIService.generateReview(sample.metadata(), sample.files(), riskScores);

        cacheService.put(cacheKey, reviewResult, cacheService.ttlForSamplePr());

        return ResponseEntity.ok(Map.of(
                "sampleId", request.sampleId(),
                "repository", sample.metadata().owner() + "/" + sample.metadata().repo(),
                "pullRequestNumber", sample.metadata().pullNumber(),
                "title", sample.metadata().title(),
                "diffHash", diffHash,
                "cacheHit", false,
                "overallRisk", overallRisk,
                "riskScores", riskScores,
                "result", reviewResult
        ));
    }

    // Request body for analyze-sample endpoint only; kept here to avoid a separate file
    record AnalyzeSampleRequest(@NotBlank String sampleId) {}
}
