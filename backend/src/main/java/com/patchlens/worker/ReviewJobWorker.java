package com.patchlens.worker;

import com.patchlens.config.RabbitMQConfig;
import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.JobStatus;
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
 *   COMPLETED           results + rendered comment stored atomically; comment delivered
 *                       (and retried independently by ReliabilityScheduler)
 * </pre>
 * Every transition is fenced by the job lease, so two deliveries of the same message can never
 * both process the job. A heartbeat renews the lease while the worker runs.
 * <pre>
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
    private final CommentDeliveryService commentDeliveryService;

    public ReviewJobWorker(ReviewJobService reviewJobService,
                           ReviewService reviewService,
                           PatchGenerationService patchGenerationService,
                           PatchPipelineService patchPipelineService,
                           GitHubService gitHubService,
                           GitHubSnapshotService snapshotService,
                           ReviewCommentService reviewCommentService,
                           CommentDeliveryService commentDeliveryService) {
        this.reviewJobService = reviewJobService;
        this.reviewService = reviewService;
        this.patchGenerationService = patchGenerationService;
        this.patchPipelineService = patchPipelineService;
        this.gitHubService = gitHubService;
        this.snapshotService = snapshotService;
        this.reviewCommentService = reviewCommentService;
        this.commentDeliveryService = commentDeliveryService;
    }

    @RabbitListener(queues = RabbitMQConfig.REVIEW_JOBS_QUEUE)
    public void process(ReviewJobMessage message) {
        // Atomically claim the job for this delivery. Empty when the job is terminal (a duplicate
        // or redelivered message) or another delivery holds a live lease — ack and drop; if that
        // worker crashes, its lease expires and ReliabilityScheduler republishes the job.
        Optional<JobLease> claimed = reviewJobService.claim(message.jobId());
        if (claimed.isEmpty()) {
            log.info("Job {} is terminal or owned by another delivery — skipping message", message.jobId());
            return;
        }
        boolean completed;
        try (JobLease lease = claimed.get()) {
            String headSha = reviewJobService.findById(message.jobId()).map(ReviewJob::getHeadSha).orElse(null);
            completed = run(lease, message, headSha);
        } catch (JobLease.LeaseLostException e) {
            // Another delivery took over after our lease expired; it owns the results now.
            log.warn("{} — abandoning this delivery without writing results", e.getMessage());
            com.patchlens.observability.PatchLensMetrics.increment(com.patchlens.observability.PatchLensMetrics.JOB_LEASE_LOST);
            return;
        }
        if (completed) {
            // First delivery attempt inline; failures are retried by ReliabilityScheduler
            // without re-running analysis.
            commentDeliveryService.deliver(message.jobId());
        }
    }

    /** @return true if the job reached COMPLETED */
    private boolean run(JobLease lease, ReviewJobMessage message, String headSha) {
        try {
            // Pin the exact revision under review. Webhook jobs carry head.sha; otherwise use the
            // PR's current head, resolved once, so analysis and validation target the same code.
            String pinnedSha = GitHubSnapshotService.isFullSha(headSha)
                    ? headSha
                    : gitHubService.fetchHeadSha(message.owner(), message.repo(), message.pullNumber()).orElse(null);

            // Stage 1 — Issue Detection (diff understanding + RAG retrieval + LLM findings)
            reviewJobService.advance(lease, JobStatus.GENERATING_FINDINGS);
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
                reviewJobService.supersede(lease, "PR head moved past " + pinnedSha + " during analysis");
                return false;
            }

            // Stage 2 — Patch Generation (HIGH/MEDIUM findings only)
            reviewJobService.advance(lease, JobStatus.GENERATING_PATCHES);
            List<PatchGenerationService.Candidate> candidates = patchGenerationService.generateCandidates(
                    outcome.findings(),
                    outcome.changedFiles(),
                    outcome.contextChunks()
            );

            // Stage 3 — Sandbox validation + bounded repair against the pinned snapshot
            reviewJobService.advance(lease, JobStatus.VALIDATING_PATCHES);
            RepositorySnapshot snapshot = snapshotFor(message.owner(), message.repo(), pinnedSha);
            List<PatchSuggestion> patches = candidates.stream()
                    .map(c -> {
                        lease.ensureHeld();
                        return patchPipelineService.validateAndRepair(c.finding(), c.patch(), snapshot).patch();
                    })
                    .toList();

            // Stage 4 — Persist results and the rendered comment atomically; delivery follows.
            ReviewJobService.CommentPlan plan = reviewCommentService.canPost()
                    ? reviewCommentService.render(message.jobId(), outcome.findings(), patches, outcome.coverage())
                            .map(ReviewJobService.CommentPlan::deliver).orElse(ReviewJobService.CommentPlan.none())
                    : ReviewJobService.CommentPlan.none();
            String resultJson = reviewService.toJson(outcome.base().reviewResult());
            reviewJobService.complete(lease, outcome.base().diffHash(), resultJson,
                    reviewService.toJson(outcome.coverage()), outcome.findings().size(), plan);
            return true;

        } catch (JobLease.LeaseLostException e) {
            throw e;

        } catch (GitHubApiException e) {
            // GitHub API errors are unrecoverable (404, 401) — mark failed, don't requeue
            reviewJobService.fail(lease, e.getMessage());
            throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                    "GitHub API error — not retrying: " + Redactor.redact(e.getMessage()), e);

        } catch (RuntimeException e) {
            // Transient failure — release the lease and let Spring AMQP retry
            reviewJobService.fail(lease, e.getMessage());
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
