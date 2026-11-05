package com.patchlens.service;

import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.CommentStatus;
import com.patchlens.model.ReviewJob;
import com.patchlens.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Delivers a completed job's stored review comment, durably and independently of analysis.
 *
 * <p>Each attempt claims the comment (status DELIVERING with a short lease), calls
 * {@link ReviewCommentService#deliver}, and records DELIVERED / SKIPPED_STALE, or a failure:
 * transient errors (timeouts, 5xx, rate limits) become FAILED_RETRYABLE with exponential
 * backoff; permanent errors (e.g. 404, 422) or an exhausted budget become FAILED_PERMANENT.
 * Retries are driven by {@link ReliabilityScheduler} and survive restarts because all state is
 * in the database. Duplicates are prevented by the comment's hidden job marker.
 */
@Service
public class CommentDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(CommentDeliveryService.class);

    private final ReviewJobService jobs;
    private final ReviewCommentService comments;

    public CommentDeliveryService(ReviewJobService jobs, ReviewCommentService comments) {
        this.jobs = jobs;
        this.comments = comments;
    }

    /** Attempts delivery if due. Never throws. Returns the resulting comment status, if attempted. */
    public Optional<CommentStatus> deliver(UUID jobId) {
        Optional<ReviewJob> claimed;
        try {
            claimed = jobs.claimCommentDelivery(jobId);
        } catch (RuntimeException e) {
            log.warn("Could not claim comment delivery for job {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
        if (claimed.isEmpty()) return Optional.empty();
        ReviewJob job = claimed.get();

        try {
            ReviewCommentService.Delivery d = comments.deliver(jobId, job.getHeadSha(),
                    job.getRepositoryOwner(), job.getRepositoryName(), job.getPullRequestNumber(), job.getCommentBody());
            if (d instanceof ReviewCommentService.Delivered delivered) {
                jobs.recordCommentOutcome(jobId, CommentStatus.DELIVERED, delivered.commentId(), null);
                com.patchlens.observability.PatchLensMetrics.increment(com.patchlens.observability.PatchLensMetrics.COMMENT_DELIVERY, "status", "DELIVERED");
                return Optional.of(CommentStatus.DELIVERED);
            }
            ReviewCommentService.SkippedStale stale = (ReviewCommentService.SkippedStale) d;
            jobs.recordCommentOutcome(jobId, CommentStatus.SKIPPED_STALE, null, "PR head is now " + stale.currentHead());
            com.patchlens.observability.PatchLensMetrics.increment(com.patchlens.observability.PatchLensMetrics.COMMENT_DELIVERY, "status", "SKIPPED_STALE");
            return Optional.of(CommentStatus.SKIPPED_STALE);
        } catch (GitHubApiException e) {
            return Optional.of(recordFailure(jobId, e.getMessage(), e.isRetryable()));
        } catch (RuntimeException e) {
            // No response (timeout, connection reset): the outcome is ambiguous. Retrying is safe
            // because the next attempt finds the marker if GitHub did create the comment.
            return Optional.of(recordFailure(jobId, e.getClass().getSimpleName() + ": " + e.getMessage(), true));
        }
    }

    private CommentStatus recordFailure(UUID jobId, String error, boolean retryable) {
        CommentStatus s = jobs.recordCommentFailure(jobId, error, retryable);
        com.patchlens.observability.PatchLensMetrics.increment(com.patchlens.observability.PatchLensMetrics.COMMENT_DELIVERY, "status", s.name());
        log.warn("Comment delivery for job {} failed ({}): {}", jobId, s, Redactor.redact(error));
        return s;
    }
}
