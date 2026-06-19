package com.patchlens.dto;

import com.patchlens.model.JobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP/SSE response payload for job status queries.
 */
public record JobStatusResponse(
        UUID jobId,
        JobStatus status,
        Instant createdAt,
        Instant updatedAt,
        String result,
        String errorMessage
) {}
