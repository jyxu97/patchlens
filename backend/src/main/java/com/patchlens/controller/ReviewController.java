package com.patchlens.controller;

import com.patchlens.dto.AnalyzePullRequestRequest;
import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.ReviewResult;
import com.patchlens.repository.ReviewSessionRepository;
import com.patchlens.service.GitHubPrUrlParser;
import com.patchlens.service.ReviewService;
import com.patchlens.service.SamplePrLoader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final SamplePrLoader samplePrLoader;
    private final GitHubPrUrlParser urlParser;
    private final ReviewService reviewService;
    private final ReviewSessionRepository sessionRepository;

    public ReviewController(SamplePrLoader samplePrLoader,
                            GitHubPrUrlParser urlParser,
                            ReviewService reviewService,
                            ReviewSessionRepository sessionRepository) {
        this.samplePrLoader = samplePrLoader;
        this.urlParser = urlParser;
        this.reviewService = reviewService;
        this.sessionRepository = sessionRepository;
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

        ReviewService.AnalysisOutcome outcome;
        try {
            outcome = reviewService.runAnalysis(pr.owner(), pr.repo(), pr.pullNumber(), request.pullRequestUrl());
        } catch (GitHubApiException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getErrorCode(),
                    "message", e.getMessage()
            ));
        }

        return ResponseEntity.ok(outcomeToMap(outcome));
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

        ReviewService.AnalysisOutcome outcome = reviewService.runSampleAnalysis(request.sampleId(), sample);

        Map<String, Object> response = new HashMap<>(outcomeToMap(outcome));
        response.put("sampleId", request.sampleId());
        return ResponseEntity.ok(response);
    }

    /**
     * Fetches a previously saved review by its session ID.
     * GET /api/reviews/{reviewSessionId}
     */
    @GetMapping("/{reviewSessionId}")
    public ResponseEntity<?> getReview(@PathVariable UUID reviewSessionId) {
        return sessionRepository.findById(reviewSessionId)
                .map(session -> {
                    ReviewResult result = reviewService.fromJson(session.getResultJson());
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

    private Map<String, Object> outcomeToMap(ReviewService.AnalysisOutcome outcome) {
        Map<String, Object> map = new HashMap<>();
        if (outcome.reviewSessionId() != null) {
            map.put("reviewSessionId", outcome.reviewSessionId());
        }
        map.put("repository", outcome.owner() + "/" + outcome.repo());
        map.put("pullRequestNumber", outcome.pullNumber());
        map.put("title", outcome.title());
        map.put("diffHash", outcome.diffHash());
        map.put("cacheHit", outcome.cacheHit());
        map.put("indexing", outcome.indexing());
        map.put("overallRisk", outcome.overallRisk());
        map.put("riskScores", outcome.riskScores());
        map.put("result", outcome.reviewResult());
        map.put("retrievedContext", outcome.retrievedContext().stream()
                .map(c -> Map.of(
                        "filePath", c.filePath(),
                        "contentPreview", c.contentPreview(),
                        "similarityScore", Math.round(c.similarityScore() * 1000.0) / 1000.0
                ))
                .toList());
        return map;
    }

    // Request body for analyze-sample endpoint only
    record AnalyzeSampleRequest(@NotBlank String sampleId) {}
}
