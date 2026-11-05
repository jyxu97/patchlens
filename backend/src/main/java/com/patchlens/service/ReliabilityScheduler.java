package com.patchlens.service;

import com.patchlens.model.CommentStatus;
import com.patchlens.model.JobStatus;
import com.patchlens.repository.ReviewJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Periodic recovery for failure windows that at-least-once messaging alone cannot close:
 * <ul>
 *   <li><b>Outbox</b> — PENDING jobs that were never published (broker was down) or were
 *       published long ago without being picked up are republished.</li>
 *   <li><b>Crashed workers</b> — in-progress jobs whose lease expired are republished; the new
 *       delivery claims the expired lease atomically.</li>
 *   <li><b>Comments</b> — PENDING / FAILED_RETRYABLE comments that are due, and deliveries
 *       abandoned mid-attempt, are retried (no LLM work is repeated).</li>
 * </ul>
 * All state lives in the database, so recovery also covers process restarts. Running on several
 * instances is safe: duplicate publishes are ignored by the worker claim and comment attempts
 * are claimed under a row lock.
 */
@Component
public class ReliabilityScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityScheduler.class);
    private static final int BATCH = 50;

    private final ReviewJobRepository repository;
    private final ReviewJobService jobs;
    private final JobPublisher publisher;
    private final CommentDeliveryService commentDelivery;
    private final Duration pendingStaleAfter;
    private Clock clock = Clock.systemUTC();

    public ReliabilityScheduler(ReviewJobRepository repository, ReviewJobService jobs,
                                JobPublisher publisher, CommentDeliveryService commentDelivery,
                                @Value("${patchlens.recovery.pending-stale-seconds:600}") long pendingStaleSeconds) {
        this.repository = repository;
        this.jobs = jobs;
        this.publisher = publisher;
        this.commentDelivery = commentDelivery;
        this.pendingStaleAfter = Duration.ofSeconds(pendingStaleSeconds);
    }

    void setClock(Clock clock) { this.clock = clock; }

    @Scheduled(fixedDelayString = "${patchlens.recovery.interval-ms:30000}",
               initialDelayString = "${patchlens.recovery.initial-delay-ms:15000}")
    public void run() {
        try { republishLostJobs(); } catch (RuntimeException e) { log.warn("Job recovery pass failed: {}", e.getMessage()); }
        try { retryDueComments(); }  catch (RuntimeException e) { log.warn("Comment retry pass failed: {}", e.getMessage()); }
    }

    /** @return number of jobs republished */
    public int republishLostJobs() {
        Instant now = clock.instant();
        List<UUID> ids = repository.findRecoverable(JobStatus.PENDING, EnumSet.copyOf(ReviewJobService.IN_PROGRESS),
                now, now.minus(pendingStaleAfter), now.minus(jobs.leaseDuration()), PageRequest.of(0, BATCH));
        int published = 0;
        for (UUID id : ids) {
            var job = repository.findById(id);
            if (job.isPresent() && publisher.publish(job.get())) published++;
        }
        if (!ids.isEmpty()) log.info("Recovery: republished {}/{} lost or abandoned job(s)", published, ids.size());
        return published;
    }

    /** @return number of comment delivery attempts made */
    public int retryDueComments() {
        List<UUID> ids = repository.findCommentsDue(
                EnumSet.of(CommentStatus.PENDING, CommentStatus.FAILED_RETRYABLE), CommentStatus.DELIVERING,
                clock.instant(), PageRequest.of(0, BATCH));
        int attempted = 0;
        for (UUID id : ids) {
            if (commentDelivery.deliver(id).isPresent()) attempted++;
        }
        return attempted;
    }
}
