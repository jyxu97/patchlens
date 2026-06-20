package com.patchlens.controller;

import com.patchlens.dto.JobStatusResponse;
import com.patchlens.model.ReviewJob;
import com.patchlens.service.JobStatusEmitter;
import com.patchlens.service.ReviewJobService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final ReviewJobService reviewJobService;
    private final JobStatusEmitter jobStatusEmitter;

    public JobController(ReviewJobService reviewJobService, JobStatusEmitter jobStatusEmitter) {
        this.reviewJobService = reviewJobService;
        this.jobStatusEmitter = jobStatusEmitter;
    }

    /**
     * Returns the current status of a job.
     * GET /api/jobs/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<JobStatusResponse> getJob(@PathVariable UUID id) {
        return reviewJobService.findById(id)
                .map(job -> ResponseEntity.ok(toResponse(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * SSE stream that pushes job status updates until the job reaches a terminal state.
     * GET /api/jobs/{id}/stream
     *
     * Events are named "job-status" and contain a JobStatusResponse JSON payload.
     * The stream auto-closes when the job reaches COMPLETED, FAILED, or DEAD_LETTER.
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJob(@PathVariable UUID id) {
        // Register the emitter before checking job state to avoid a race condition
        SseEmitter emitter = jobStatusEmitter.subscribe(id);

        // If the job is already in a terminal state, emit once and complete immediately
        reviewJobService.findById(id).ifPresent(job -> {
            jobStatusEmitter.emit(job);
        });

        return emitter;
    }

    private JobStatusResponse toResponse(ReviewJob job) {
        return new JobStatusResponse(
                job.getId(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getResultJson(),
                job.getErrorMessage()
        );
    }
}
