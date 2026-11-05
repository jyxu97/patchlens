package com.patchlens.service;

import com.patchlens.model.CommentStatus;
import com.patchlens.model.JobStatus;
import com.patchlens.model.ReviewJob;
import com.patchlens.repository.ReviewJobRepository;
import com.patchlens.util.Redactor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of ReviewJob entities.
 *
 * <p><b>Ownership.</b> RabbitMQ delivery is at-least-once, so the same job can be delivered to
 * several consumers. {@link #claim} takes an exclusive, expiring lease on the job row under a
 * row lock ({@code SELECT … FOR UPDATE}); only one delivery can hold it. Every later transition
 * is fenced by the lease token, so a worker whose lease expired and was taken over can no longer
 * write. A crashed worker's lease simply expires and the recovery scheduler republishes the job.
 *
 * <p>All state transitions persist immediately; SSE notifications are emitted after each save.
 */
@Service
public class ReviewJobService {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobService.class);

    /** Terminal analysis states: a job in one of these is never processed again. */
    public static final Set<JobStatus> TERMINAL = EnumSet.of(JobStatus.COMPLETED, JobStatus.DEAD_LETTER, JobStatus.SUPERSEDED);
    /** States in which a worker is (or was, if it crashed) actively processing the job. */
    public static final Set<JobStatus> IN_PROGRESS = EnumSet.of(JobStatus.PROCESSING, JobStatus.GENERATING_FINDINGS,
            JobStatus.GENERATING_PATCHES, JobStatus.VALIDATING_PATCHES);

    static final int MAX_COMMENT_ATTEMPTS = 6;
    static final Duration COMMENT_LEASE = Duration.ofMinutes(2);

    // @Lazy breaks the circular reference; Spring still injects the transactional proxy
    @Lazy
    @Autowired
    private ReviewJobService self;

    private final ReviewJobRepository repository;
    private final JobStatusEmitter emitter;
    private final Duration leaseDuration;
    private final ScheduledExecutorService heartbeats;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public ReviewJobService(ReviewJobRepository repository, JobStatusEmitter emitter,
                            @Value("${patchlens.worker.lease-seconds:120}") long leaseSeconds) {
        this.repository = repository;
        this.emitter = emitter;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "job-lease-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public ReviewJobService(ReviewJobRepository repository, JobStatusEmitter emitter) {
        this(repository, emitter, 120);
    }

    /** For tests: control time. */
    void setClock(Clock clock) { this.clock = clock; }

    public Duration leaseDuration() { return leaseDuration; }

    private ReviewJobService proxy() { return self != null ? self : this; }

    @PreDestroy
    void shutdown() { heartbeats.shutdownNow(); }

    // =====================================================================
    // Creation (webhook) — unchanged idempotency semantics
    // =====================================================================

    /**
     * Returns an existing job for this PR commit, or creates a new PENDING one.
     *
     * <p>Concurrency-safe: {@code tryInsertJob()} runs in its own REQUIRES_NEW transaction
     * so its commit/rollback is isolated from the caller's transaction.  If a concurrent
     * request already inserted the same (owner, repo, pullNumber, headSha), the unique
     * constraint triggers a {@link DataIntegrityViolationException}, which is caught here;
     * the loser thread then re-queries and returns the winner's job.  Exactly one job row
     * is created regardless of how many concurrent duplicate webhooks arrive.
     */
    @Transactional
    public ReviewJob createOrFind(String owner, String repo, int pullNumber,
                                  String pullRequestUrl, String headSha) {
        Optional<ReviewJob> existing = repository
                .findFirstByRepositoryOwnerAndRepositoryNameAndPullRequestNumberAndHeadSha(
                        owner, repo, pullNumber, headSha);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return proxy().tryInsertJob(owner, repo, pullNumber, pullRequestUrl, headSha);
        } catch (DataIntegrityViolationException e) {
            return repository
                    .findFirstByRepositoryOwnerAndRepositoryNameAndPullRequestNumberAndHeadSha(
                            owner, repo, pullNumber, headSha)
                    .orElseThrow(() -> new IllegalStateException(
                            "Concurrent insert conflict but job not found", e));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewJob tryInsertJob(String owner, String repo, int pullNumber,
                                  String pullRequestUrl, String headSha) {
        ReviewJob job = new ReviewJob(owner, repo, pullNumber, pullRequestUrl, headSha);
        ReviewJob saved = repository.saveAndFlush(job); // flush immediately to surface constraint violation
        emitter.emit(saved);
        return saved;
    }

    /** Outbox bookkeeping: the job's message was handed to the broker. */
    @Transactional
    public void markPublished(UUID jobId) {
        repository.lockById(jobId).ifPresent(job -> {
            job.setPublishedAt(clock.instant());
            job.setPublishAttempts(job.getPublishAttempts() + 1);
            repository.save(job);
        });
    }

    // =====================================================================
    // Worker ownership
    // =====================================================================

    /**
     * Atomically claims the job for this delivery: transitions it to PROCESSING, increments the
     * attempt counter and takes the lease — unless the job is terminal or another delivery holds
     * an unexpired lease.
     *
     * @return the lease (with a running heartbeat), or empty if the job must not be processed now
     */
    @Transactional
    public Optional<JobLease> claim(UUID jobId) {
        ReviewJob job = repository.lockById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("ReviewJob not found: " + jobId));
        Instant now = clock.instant();
        if (TERMINAL.contains(job.getStatus())) return Optional.empty();
        if (job.getLeaseOwner() != null && job.getLeaseExpiresAt() != null && job.getLeaseExpiresAt().isAfter(now)) {
            log.info("Job {} is leased by another delivery until {} — not claiming", jobId, job.getLeaseExpiresAt());
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        job.setLease(token, now.plus(leaseDuration));
        job.setStatus(JobStatus.PROCESSING);
        job.setAttemptCount(job.getAttemptCount() + 1);
        emitter.emit(repository.save(job));

        JobLease lease = new JobLease(jobId, token);
        long periodMs = Math.max(1000, leaseDuration.toMillis() / 3);
        lease.attachHeartbeat(heartbeats.scheduleAtFixedRate(() -> heartbeat(lease), periodMs, periodMs, TimeUnit.MILLISECONDS));
        return Optional.of(lease);
    }

    private void heartbeat(JobLease lease) {
        try {
            if (!proxy().renew(lease.jobId(), lease.token())) {
                lease.markLost();
                lease.close();
            }
        } catch (RuntimeException e) {
            log.warn("Lease heartbeat for job {} failed: {}", lease.jobId(), e.getMessage());
        }
    }

    /** Extends the lease if {@code token} still owns it. */
    @Transactional
    public boolean renew(UUID jobId, String token) {
        Optional<ReviewJob> job = repository.lockById(jobId);
        if (job.isEmpty() || !token.equals(job.get().getLeaseOwner())) return false;
        job.get().setLease(token, clock.instant().plus(leaseDuration));
        repository.save(job.get());
        return true;
    }

    /** Fenced stage transition (GENERATING_FINDINGS, GENERATING_PATCHES, VALIDATING_PATCHES). */
    @Transactional
    public ReviewJob advance(JobLease lease, JobStatus status) {
        ReviewJob job = lockOwned(lease);
        job.setStatus(status);
        job.setLease(lease.token(), clock.instant().plus(leaseDuration));
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /** What to do with the job's GitHub comment once analysis completes. */
    public record CommentPlan(CommentStatus status, String body) {
        public static CommentPlan none() { return new CommentPlan(CommentStatus.NOT_REQUIRED, null); }
        public static CommentPlan deliver(String body) { return new CommentPlan(CommentStatus.PENDING, body); }
    }

    /**
     * Fenced completion: stores results and the rendered comment (status PENDING) in one
     * transaction and releases the lease. Comment delivery happens afterwards and is retried
     * independently, so a GitHub outage never forces the analysis to run again.
     */
    @Transactional
    public ReviewJob complete(JobLease lease, String diffHash, String resultJson, int findingCount, CommentPlan plan) {
        ReviewJob job = lockOwned(lease);
        job.setDiffHash(diffHash);
        job.setResultJson(resultJson);
        job.setFindingCount(findingCount);
        job.setStatus(JobStatus.COMPLETED);
        job.setCompletedAt(clock.instant());
        job.setCommentStatus(plan.status());
        job.setCommentBody(plan.body());
        job.setCommentAttempts(0);
        job.setCommentNextAttemptAt(clock.instant());
        job.clearLease();
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /**
     * Fenced failure: records the error and releases the lease so a retry (same or another
     * delivery) can claim the job. A no-op if this delivery no longer owns the job.
     */
    @Transactional
    public void fail(JobLease lease, String errorMessage) {
        repository.lockById(lease.jobId()).ifPresent(job -> {
            if (!lease.token().equals(job.getLeaseOwner())) return;
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(Redactor.redact(errorMessage));
            job.clearLease();
            emitter.emit(repository.save(job));
        });
    }

    /**
     * Fenced: a newer commit was pushed while the job ran, so its results would describe code
     * that is no longer the PR head. Terminal; no comment is posted.
     */
    @Transactional
    public ReviewJob supersede(JobLease lease, String reason) {
        ReviewJob job = lockOwned(lease);
        job.setStatus(JobStatus.SUPERSEDED);
        job.setErrorMessage(reason);
        job.setCompletedAt(clock.instant());
        job.setCommentStatus(CommentStatus.NOT_REQUIRED);
        job.clearLease();
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /** Transitions the job to DEAD_LETTER (all retries exhausted). Called by the AMQP recoverer. */
    @Transactional
    public ReviewJob markDeadLetter(UUID jobId, String errorMessage) {
        ReviewJob job = findOrThrow(jobId);
        if (job.getStatus() == JobStatus.COMPLETED || job.getStatus() == JobStatus.SUPERSEDED) return job;
        job.setStatus(JobStatus.DEAD_LETTER);
        job.setErrorMessage(Redactor.redact(errorMessage));
        job.setCompletedAt(clock.instant());
        job.clearLease();
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    private ReviewJob lockOwned(JobLease lease) {
        lease.ensureHeld();
        ReviewJob job = repository.lockById(lease.jobId())
                .orElseThrow(() -> new IllegalArgumentException("ReviewJob not found: " + lease.jobId()));
        if (!lease.token().equals(job.getLeaseOwner())) {
            lease.markLost();
            throw new JobLease.LeaseLostException(lease.jobId());
        }
        return job;
    }

    // =====================================================================
    // Comment delivery state
    // =====================================================================

    /**
     * Claims the job's comment for one delivery attempt (status → DELIVERING, short lease).
     * Returns empty if the comment is not due, already delivered, or being delivered by someone else.
     */
    @Transactional
    public Optional<ReviewJob> claimCommentDelivery(UUID jobId) {
        Optional<ReviewJob> locked = repository.lockById(jobId);
        if (locked.isEmpty()) return Optional.empty();
        ReviewJob job = locked.get();
        Instant now = clock.instant();
        CommentStatus s = job.getCommentStatus();
        boolean due = (s == CommentStatus.PENDING || s == CommentStatus.FAILED_RETRYABLE)
                && (job.getCommentNextAttemptAt() == null || !job.getCommentNextAttemptAt().isAfter(now));
        boolean abandoned = s == CommentStatus.DELIVERING
                && job.getCommentLeaseUntil() != null && job.getCommentLeaseUntil().isBefore(now);
        if (!due && !abandoned) return Optional.empty();
        job.setCommentStatus(CommentStatus.DELIVERING);
        job.setCommentLeaseUntil(now.plus(COMMENT_LEASE));
        job.setCommentAttempts(job.getCommentAttempts() + 1);
        return Optional.of(repository.save(job));
    }

    @Transactional
    public void recordCommentOutcome(UUID jobId, CommentStatus status, Long commentId, String reason) {
        repository.lockById(jobId).ifPresent(job -> {
            job.setCommentStatus(status);
            if (commentId != null) job.setCommentId(commentId);
            job.setCommentLastError(reason == null ? null : Redactor.redactTail(reason, 2000));
            job.setCommentLeaseUntil(null);
            repository.save(job);
        });
    }

    /** Records a failed attempt; retryable failures back off exponentially until the budget runs out. */
    @Transactional
    public CommentStatus recordCommentFailure(UUID jobId, String error, boolean retryable) {
        ReviewJob job = repository.lockById(jobId).orElseThrow();
        boolean exhausted = job.getCommentAttempts() >= MAX_COMMENT_ATTEMPTS;
        CommentStatus next = retryable && !exhausted ? CommentStatus.FAILED_RETRYABLE : CommentStatus.FAILED_PERMANENT;
        job.setCommentStatus(next);
        job.setCommentLastError(Redactor.redactTail(error, 2000));
        job.setCommentLeaseUntil(null);
        job.setCommentNextAttemptAt(clock.instant().plus(commentBackoff(job.getCommentAttempts())));
        repository.save(job);
        return next;
    }

    static Duration commentBackoff(int attempts) {
        long seconds = 30L << Math.min(Math.max(attempts - 1, 0), 6); // 30s, 60s, 2m, 4m, 8m, 16m, 32m
        return Duration.ofSeconds(Math.min(seconds, 30 * 60));
    }

    public Optional<ReviewJob> findById(UUID jobId) {
        return repository.findById(jobId);
    }

    private ReviewJob findOrThrow(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("ReviewJob not found: " + jobId));
    }
}
