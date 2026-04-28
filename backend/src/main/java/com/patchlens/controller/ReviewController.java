package com.patchlens.controller;

import com.patchlens.service.SamplePrLoader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final SamplePrLoader samplePrLoader;

    public ReviewController(SamplePrLoader samplePrLoader) {
        this.samplePrLoader = samplePrLoader;
    }

    /**
     * Analyzes a sample PR by its fixture ID.
     * POST /api/reviews/analyze-sample
     * Body: { "sampleId": "redis-session-cache" }
     */
    @PostMapping("/analyze-sample")
    public ResponseEntity<?> analyzeSample(
            @Valid @RequestBody AnalyzeSampleRequest request) {

        SamplePrLoader.SamplePr sample;
        try {
            sample = samplePrLoader.load(request.sampleId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "SAMPLE_NOT_FOUND",
                           "message", "Unknown sample ID: " + request.sampleId())
            );
        }

        // Return raw sample data for now.
        // Risk scoring and AI generation will be wired in later milestones.
        return ResponseEntity.ok(Map.of(
                "sampleId", request.sampleId(),
                "repository", sample.metadata().owner() + "/" + sample.metadata().repo(),
                "pullRequestNumber", sample.metadata().pullNumber(),
                "title", sample.metadata().title(),
                "changedFiles", sample.files().size(),
                "files", sample.files()
        ));
    }

    // Request body for this endpoint only; kept here to avoid a separate file
    record AnalyzeSampleRequest(@NotBlank String sampleId) {}
}