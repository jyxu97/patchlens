package com.patchlens.worker;

import com.patchlens.config.RabbitMQConfig;
import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.PatchSuggestion;
import com.patchlens.model.ReviewJob;
import com.patchlens.sandbox.GitHubSnapshotService;
import com.patchlens.sandbox.RepositorySnapshot;
import com.patchlens.service.*;
import com.patchlens.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Async RabbitMQ consumer for the review.jobs queue.
 *
 * <p>Pipeline stages (each maps to a JobStatus transition):
 * <pre>
 *   PROCESSING          claim the job; pin the commit SHA under review
 *   GENERATING_FINDINGS runAnalysisV2 (diff + RAG + LLM findings + deterministic grounding)
 *                       → SUPERSEDED if the PR head moved during analysis
 *   GENERATING_PATCHES  initial candidate diff per HIGH/MEDIUM finding (LangChain4j)
 *   VALIDATING_PATCHES  policy → Docker sandbox (apply, compile, tests) on a snapshot of the
 *                       pinned SHA → bounded repair (≤2), each attempt in a fresh workspace
 *   COMPLETED           post/update the GitHub review comment
 * </pre>
 *
 * NOTE: intentionally NOT @Transactional — wrapping long model calls and sandbox builds in a
 * single transaction would exhaust the DB connection pool under concurrent load.
 */
@Component
public class ReviewJobWorker {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobWorker.class);

    private final ReviewJobService reviewJobService;
    private final ReviewService reviewService;
    private final PatchGenerationService patchGenerationService;
    private final PatchPipelineService patchPipelineService;
    private final GitHubService gitHubService;
    private final GitHubSnapshotService snapshotService;
    private final ReviewCommentService reviewCommentService;

    public ReviewJobWorker(ReviewJobService reviewJobService,
                           ReviewService reviewService,
                           PatchGenerationService patchGenerationService,
                           PatchPipelineService patchPipelineService,
                           GitHubService gitHubService,
                           GitHubSnapshotService snapshotService,
                           ReviewCommentService reviewCommentService) {
        this.reviewJobService = reviewJobService;
        this.reviewService = reviewService;
        this.patchGenerationService = patchGenerationService;
        this.patchPipelineService = patchPipelineService;
        this.gitHubService = gitHubService;
        this.snapshotService = snapshotService;
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
            // Pin the exact revision under review. Webhook jobs carry head.sha; otherwise use the
            // PR's current head, resolved once, so analysis and validation target the same code.
            String pinnedSha = GitHubSnapshotService.isFullSha(headSha)
                    ? headSha
                    : gitHubService.fetchHeadSha(message.owner(), message.repo(), message.pullNumber()).orElse(null);

            // Stage 1 — Issue Detection (diff understanding + RAG retrieval + LLM findings)
            reviewJobService.markGeneratingFindings(message.jobId());
            ReviewService.AnalysisOutcomeV2 outcome = reviewService.runAnalysisV2(
                    message.jobId(),
                    message.owner(),
                    message.repo(),
                    message.pullNumber(),
                    message.pullRequestUrl()
            );

            // The PR files endpoint always returns the *current* head. If a commit was pushed while
            // we analysed, the findings may describe newer code than the pinned SHA: stop here and
            // let the newer commit's own job review it.
            if (pinnedSha != null && headMovedPast(pinnedSha, message)) {
                reviewJobService.markSuperseded(message.jobId(),
                        "PR head moved past " + pinnedSha + " during analysis");
                return;
            }

            // Stage 2 — Patch Generation (HIGH/MEDIUM findings only)
            reviewJobService.markGeneratingPatches(message.jobId());
            List<PatchGenerationService.Candidate> candidates = patchGenerationService.generateCandidates(
                    outcome.findings(),
                    outcome.changedFiles(),
                    outcome.contextChunks()
            );

            // Stage 3 — Sandbox validation + bounded repair against the pinned snapshot
            reviewJobService.markValidatingPatches(message.jobId());
            RepositorySnapshot snapshot = snapshotFor(message.owner(), message.repo(), pinnedSha);
            List<PatchSuggestion> patches = candidates.stream()
                    .map(c -> patchPipelineService.validateAndRepair(c.finding(), c.patch(), snapshot).patch())
                    .toList();

            // Stage 4 — Post advisory review comment on the PR (no-op if no GitHub token).
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
            reviewJobService.markFailed(message.jobId(), Redactor.redact(e.getMessage()));
            throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                    "GitHub API error — not retrying: " + e.getMessage(), e);

        } catch (Exception e) {
            // Transient failure — mark as FAILED and let Spring AMQP retry
            reviewJobService.markFailed(message.jobId(), Redactor.redact(e.getMessage()));
            throw e;
        }
    }

    private boolean headMovedPast(String pinnedSha, ReviewJobMessage message) {
        return gitHubService.fetchHeadSha(message.owner(), message.repo(), message.pullNumber())
                .map(current -> !current.equals(pinnedSha))
                .orElse(false);
    }

    /**
     * Snapshot of the pinned revision. If no full SHA is known, every validation attempt fails
     * explicitly with VALIDATION_ERROR instead of validating against an arbitrary revision.
     */
    private RepositorySnapshot snapshotFor(String owner, String repo, String pinnedSha) {
        try {
            return snapshotService.snapshot(owner, repo, pinnedSha);
        } catch (IllegalArgumentException e) {
            String reason = e.getMessage();
            return new RepositorySnapshot() {
                @Override public String revision()           { return String.valueOf(pinnedSha); }
                @Override public String describe()           { return "unresolvable:" + owner + "/" + repo; }
                @Override public String dependencyCacheKey() { return owner + "/" + repo; }
                @Override public void materialize(Path emptyDir) throws IOException { throw new IOException(reason); }
            };
        }
    }
}
