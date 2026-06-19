package com.patchlens.service;

import com.patchlens.model.JobStatus;
import com.patchlens.model.ReviewJob;
import com.patchlens.repository.ReviewJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of ReviewJob entities.
 * All state transitions persist immediately; SSE notifications are emitted after each save.
 */
@Service
public class ReviewJobService {

    private final ReviewJobRepository repository;
    private final JobStatusEmitter emitter;

    public ReviewJobService(ReviewJobRepository repository, JobStatusEmitter emitter) {
        this.repository = repository;
        this.emitter = emitter;
    }

    /**
     * Creates a new PENDING job.
     * Checks for an existing PENDING/PROCESSING job for the same PR first (idempotency).
     *
     * @return existing job if one is already active, otherwise newly created job
     */
    @Transactional
    public ReviewJob createOrFind(String owner, String repo, int pullNumber, String pullRequestUrl) {
        Optional<ReviewJob> existing = repository
                .findFirstByRepositoryOwnerAndRepositoryNameAndPullRequestNumberAndStatusIn(
                        owner, repo, pullNumber,
                        List.of(JobStatus.PENDING, JobStatus.PROCESSING)
                );
        if (existing.isPresent()) {
            return existing.get();
        }

        ReviewJob job = new ReviewJob(owner, repo, pullNumber, pullRequestUrl);
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /**
     * Transitions the job to PROCESSING and increments the attempt counter.
     */
    @Transactional
    public ReviewJob markProcessing(UUID jobId) {
        ReviewJob job = findOrThrow(jobId);
        job.setStatus(JobStatus.PROCESSING);
        job.setAttemptCount(job.getAttemptCount() + 1);
        ReviewJob saved = repository.save(job);
        emitter.emit(saved);
        return saved;
    }

    /**
     * Transitions the job to COMPLETED and stores the result JSON.
     */
    @Transactional
    public ReviewJob markCompleted(UUID jobId, String diffHash, String resultJson) {
        ReviewJob job = findOrThrow(jobId);
        job.setDiffHash(diffHash);
        job.setResultJson(resultJson);
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
