package com.patchlens.worker;

import com.patchlens.config.RabbitMQConfig;
import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.PatchSuggestion;
import com.patchlens.model.ReviewJob;
import com.patchlens.service.PatchGenerationService;
import com.patchlens.service.ReviewCommentService;
import com.patchlens.service.ReviewJobService;
import com.patchlens.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

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

    private static final Logger log = LoggerFactory.getLogger(ReviewJobWorker.class);

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
        // Claim the job (each delivery = one attempt). A redelivered message for a job
        // that already finished is acked as a no-op instead of re-running the pipeline.
        Optional<ReviewJob> claimed = reviewJobService.startProcessing(message.jobId());
        if (claimed.isEmpty()) {
            log.info("Job {} already in a terminal state — skipping redelivered message", message.jobId());
            return;
        }
        String headSha = claimed.get().getHeadSha();

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

            // Stage 3 — Post advisory review comment on the PR (no-op if no GitHub token).
            // Idempotent per job and skipped if the PR head has moved past headSha.
            reviewCommentService.postReview(
                    message.jobId(), headSha,
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
