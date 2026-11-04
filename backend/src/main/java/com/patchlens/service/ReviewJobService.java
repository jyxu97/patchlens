package com.patchlens.service;

import com.patchlens.model.JobStatus;
import com.patchlens.model.ReviewJob;
import com.patchlens.repository.ReviewJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of ReviewJob entities.
 * All state transitions persist immediately; SSE notifications are emitted after each save.
 */
@Service
public class ReviewJobService {

    // @Lazy breaks the circular reference; Spring still injects the transactional proxy
    @Lazy
    @Autowired
    private ReviewJobService self;

    private final ReviewJobRepository repository;
    private final JobStatusEmitter emitter;

    public ReviewJobService(ReviewJobRepository repository, JobStatusEmitter emitter) {
        this.repository = repository;
        this.emitter = emitter;
    }

    /**
     * Returns an existing job for this PR commit, or creates a new PENDING one.
     *
     * <p>Concurrency-safe: {@code tryInsertJob()} runs in its own REQUIRES_NEW transaction
     * so its commit/rollback is isolated from the caller's transaction.  If a concurrent
     * request already inserted the same (owner, repo, pullNumber, headSha), the unique
     * constraint triggers a {@link DataIntegrityViolationException}, which is caught here;
     * the loser thread then re-queries and returns the winner's job.  Exactly one job row
     * is created regardless of how many concurrent duplicate webhooks arrive.
     *
     * @param headSha  commit SHA from pull_request.head.sha in the webhook payload
     * @return the deduplicated job for this PR commit
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
            return self.tryInsertJob(owner, repo, pullNumber, pullRequestUrl, headSha);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request won the insert race — find and return the winning job
            return repository
                    .findFirstByRepositoryOwnerAndRepositoryNameAndPullRequestNumberAndHeadSha(
                            owner, repo, pullNumber, headSha)
                    .orElseThrow(() -> new IllegalStateException(
                            "Concurrent insert conflict but job not found", e));
        }
    }

    /**
     * Inserts a new PENDING job in its own REQUIRES_NEW transaction.
     * Must be called via the Spring proxy (i.e. {@code self.tryInsertJob(...)}) so
     * that the transaction is independent — a rollback here does not mark the
     * caller's transaction as rollback-only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewJob tryInsertJob(String owner, String repo, int pullNumber,
                                  String pullRequestUrl, String headSha) {
        ReviewJob job = new ReviewJob(owner, repo, pullNumber, pullRequestUrl, headSha);
        ReviewJob saved = repository.saveAndFlush(job); // flush immediately to surface constraint violation
        emitter.emit(saved);
        return saved;
    }

    /**
     * Claims the job for processing: transitions it to PROCESSING and increments the
     * attempt counter — unless the job has already reached a terminal state.
     *
     * <p>RabbitMQ delivery is at-least-once: a worker that crashes after finishing but
     * before acking causes the same message to be redelivered. Returning empty for
     * COMPLETED / DEAD_LETTER jobs lets the worker ack the duplicate without re-running
     * the pipeline. FAILED and in-progress states are still claimable, because those are
     * exactly the states a legitimate retry (or crash redelivery) starts from.
     *
     * @return the claimed job, or empty if the job is already terminal
     */
    @Transactional
    public Optional<ReviewJob> startProcessing(UUID jobId) {
        ReviewJob job = findOrThrow(jobId);
        if (job.getStatus() == JobStatus.COMPLETED || job.getStatus() == JobStatus.DEAD_LETTER) {
            return Optional.empty();
        }
        job.setStatus(JobStatus.PROCESSING);
        job.setAttemptCount(job.getAttemptCount() + 1);
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return Optional.of(saved);
    }

    /**
     * Transitions the job to GENERATING_FINDINGS (Phase 2 multi-step pipeline).
     */
    @Transactional
    public ReviewJob markGeneratingFindings(UUID jobId) {
        ReviewJob job = findOrThrow(jobId);
        job.setStatus(JobStatus.GENERATING_FINDINGS);
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /**
     * Transitions the job to GENERATING_PATCHES (Phase 3).
     */
    @Transactional
    public ReviewJob markGeneratingPatches(UUID jobId) {
        ReviewJob job = findOrThrow(jobId);
        job.setStatus(JobStatus.GENERATING_PATCHES);
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /**
     * Transitions the job to VALIDATING_PATCHES (Phase 4).
     */
    @Transactional
    public ReviewJob markValidatingPatches(UUID jobId) {
        ReviewJob job = findOrThrow(jobId);
        job.setStatus(JobStatus.VALIDATING_PATCHES);
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /**
     * Transitions the job to COMPLETED and stores the result JSON.
     */
    @Transactional
    public ReviewJob markCompleted(UUID jobId, String diffHash, String resultJson) {
        return markCompleted(jobId, diffHash, resultJson, 0);
    }

    /**
     * Transitions the job to COMPLETED with finding count.
     */
    @Transactional
    public ReviewJob markCompleted(UUID jobId, String diffHash, String resultJson, int findingCount) {
        ReviewJob job = findOrThrow(jobId);
        job.setDiffHash(diffHash);
        job.setResultJson(resultJson);
        job.setFindingCount(findingCount);
        job.setStatus(JobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /**
     * Transitions the job to FAILED (still retriable via DLQ).
     */
    @Transactional
    public ReviewJob markFailed(UUID jobId, String errorMessage) {
        ReviewJob job = findOrThrow(jobId);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /**
     * Transitions the job to DEAD_LETTER (all retries exhausted, unrecoverable).
     */
    @Transactional
    public ReviewJob markDeadLetter(UUID jobId, String errorMessage) {
        ReviewJob job = findOrThrow(jobId);
        job.setStatus(JobStatus.DEAD_LETTER);
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(Instant.now());
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    public Optional<ReviewJob> findById(UUID jobId) {
        return repository.findById(jobId);
    }

    private ReviewJob findOrThrow(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("ReviewJob not found: " + jobId));
    }
}
