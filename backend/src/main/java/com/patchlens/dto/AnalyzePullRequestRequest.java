package com.patchlens.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/reviews/analyze.
 *
 * <p>Spring automatically deserializes the incoming JSON into this record,
 * and validates the fields before the controller method is invoked.
 * If validation fails, Spring returns 400 Bad Request immediately.
 *
 * <p>Expected JSON:
 * <pre>
 * {
 *   "pullRequestUrl": "https://github.com/owner/repo/pull/123"
 * }
 * </pre>
 */
public record AnalyzePullRequestRequest(

        /**
         * Full GitHub pull request URL.
         * Must not be null, empty, or whitespace-only.
         * Example: "https://github.com/torvalds/linux/pull/1234"
         */
        @NotBlank(message = "pullRequestUrl is required")
        String pullRequestUrl

) {}