package com.patchlens.model;

/**
 * Durable delivery state of a job's GitHub review comment, independent of the analysis status.
 * A job can be COMPLETED (analysis done) while its comment is still PENDING or being retried.
 */
public enum CommentStatus {
    /** Nothing to post (no findings, or no GitHub token configured). */
    NOT_REQUIRED,
    /** Rendered and stored; waiting for (re)delivery. */
    PENDING,
    /** A delivery attempt holds the comment lease. */
    DELIVERING,
    DELIVERED,
    /** Last attempt failed with a transient error; retried after commentNextAttemptAt. */
    FAILED_RETRYABLE,
    /** Permanent error or retry budget exhausted. */
    FAILED_PERMANENT,
    /** PR head moved past the reviewed commit before delivery. */
    SKIPPED_STALE;

    public boolean isTerminal() {
        return this == NOT_REQUIRED || this == DELIVERED || this == FAILED_PERMANENT || this == SKIPPED_STALE;
    }
}
