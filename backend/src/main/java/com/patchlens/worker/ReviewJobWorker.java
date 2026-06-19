package com.patchlens.worker;

import com.patchlens.config.RabbitMQConfig;
import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.exception.GitHubApiException;
import com.patchlens.service.ReviewJobService;
import com.patchlens.service.ReviewService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Async RabbitMQ consumer for the review.jobs queue.
 *
 * NOTE: The process() method is intentionally NOT @Transactional.
 * Wrapping a ~10s OpenAI call in a single transaction would exhaust
 * the DB connection pool.
 */
@Component
public class ReviewJobWorker {

    private final ReviewJobService reviewJobService;
    private final ReviewService reviewService;

    public ReviewJobWorker(ReviewJobService reviewJobService, ReviewService reviewService) {
        this.reviewJobService = reviewJobService;
        this.reviewService = reviewService;
    }

    @RabbitListener(queues = RabbitMQConfig.REVIEW_JOBS_QUEUE)
    public void process(ReviewJobMessage message) {
        // Transition to PROCESSING immediately (each delivery = one attempt)
        reviewJobService.markProcessing(message.jobId());

        try {
            ReviewService.AnalysisOutcome outcome = reviewService.runAnalysis(
                    message.owner(),
                    message.repo(),
                    message.pullNumber(),
                    message.pullRequestUrl()
            );

            String resultJson = reviewService.toJson(outcome.reviewResult());
            reviewJobService.markCompleted(message.jobId(), outcome.diffHash(), resultJson);

        } catch (GitHubApiException e) {
            // GitHub API errors are unrecoverable (e.g. 404, 401) — mark failed immediately
            // Spring AMQP will nack without requeue since defaultRequeueRejected=false
            reviewJobService.markFailed(message.jobId(), e.getMessage());
            throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                    "GitHub API error — not retrying: " + e.getMessage(), e);

        } catch (Exception e) {
            // Transient failure — mark as FAILED and let Spring AMQP retry
            reviewJobService.markFailed(message.jobId(), e.getMessage());
            throw e;
        }
    }
}
