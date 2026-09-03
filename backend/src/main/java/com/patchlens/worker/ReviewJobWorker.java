package com.patchlens.worker;

import com.patchlens.config.RabbitMQConfig;
import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.PatchSuggestion;
import com.patchlens.service.PatchGenerationService;
import com.patchlens.service.ReviewCommentService;
import com.patchlens.service.ReviewJobService;
import com.patchlens.service.ReviewService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Async RabbitMQ consumer for the review.jobs queue.
 *
 * Pipeline stages (each maps to a JobStatus transition):
 *   PROCESSING           → GENERATING_FINDINGS → runAnalysisV2 (diff + RAG + LLM findings)
 *   GENERATING_PATCHES   → generatePatches       (policy-validated unified diffs)
 *   COMPLETED            → postReview            (GitHub PR comment with findings + patches)
 *
 * NOTE: intentionally NOT @Transactional — wrapping a ~10s OpenAI call in a single
 * transaction would exhaust the DB connection pool under concurrent load.
 */
@Component
public class ReviewJobWorker {

    private final ReviewJobService reviewJobService;
    private final ReviewService reviewService;
    private final PatchGenerationService patchGenerationService;
    private final ReviewCommentService reviewCommentService;

    public ReviewJobWorker(ReviewJobService reviewJobService,
                           ReviewService reviewService,
                           PatchGenerationService patchGenerationService,
                           ReviewCommentService reviewCommentService) {
        this.reviewJobService = reviewJobService;
        this.reviewService = reviewService;
        this.patchGenerationService = patchGenerationService;
        this.reviewCommentService = reviewCommentService;
    }

    @RabbitListener(queues = RabbitMQConfig.REVIEW_JOBS_QUEUE)
    public void process(ReviewJobMessage message) {
        // Transition to PROCESSING immediately (each delivery = one attempt)
        reviewJobService.markProcessing(message.jobId());

        try {
            // Stage 1 — Issue Detection (diff understanding + RAG retrieval + LLM findings)
            reviewJobService.markGeneratingFindings(message.jobId());
            ReviewService.AnalysisOutcomeV2 outcome = reviewService.runAnalysisV2(
                    message.jobId(),
                    message.owner(),
                    message.repo(),
                    message.pullNumber(),
                    message.pullRequestUrl()
            );

            // Stage 2 — Patch Generation (HIGH/MEDIUM findings only)
            reviewJobService.markGeneratingPatches(message.jobId());
            List<PatchSuggestion> patches = patchGenerationService.generatePatches(
                    outcome.findings(),
                    outcome.changedFiles(),
                    outcome.contextChunks()
            );

            // Stage 3 — Post advisory review comment on the PR (no-op if no GitHub token)
            reviewCommentService.postReview(
                    message.owner(), message.repo(), message.pullNumber(),
                    outcome.findings(), patches
            );

            // Persist final job state
            String resultJson = reviewService.toJson(outcome.base().reviewResult());
            reviewJobService.markCompleted(
                    message.jobId(), outcome.base().diffHash(),
                    resultJson, outcome.findings().size()
            );

        } catch (GitHubApiException e) {
            // GitHub API errors are unrecoverable (404, 401) — mark failed, don't requeue
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
