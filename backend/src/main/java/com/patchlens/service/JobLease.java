package com.patchlens.service;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * Exclusive, time-bounded ownership of one review job by one message delivery.
 *
 * <p>The lease is renewed by a background heartbeat while the worker runs. If a renewal finds
 * another owner (the lease expired and was reclaimed), the lease is marked lost and the next
 * fenced state transition throws {@link LeaseLostException}, so a stalled worker can never
 * overwrite the results of the worker that took over.
 */
public final class JobLease implements AutoCloseable {

    private final UUID jobId;
    private final String token;
    private volatile boolean lost;
    private volatile ScheduledFuture<?> heartbeat;

    JobLease(UUID jobId, String token) {
        this.jobId = jobId;
        this.token = token;
    }

    public UUID jobId()   { return jobId; }
    public String token() { return token; }
    public boolean isLost() { return lost; }

    void markLost() { lost = true; }

    void attachHeartbeat(ScheduledFuture<?> f) { this.heartbeat = f; }

    public void ensureHeld() {
        if (lost) throw new LeaseLostException(jobId);
    }

    /** Stops the heartbeat. Releasing ownership happens in the terminal transition itself. */
    @Override
    public void close() {
        ScheduledFuture<?> f = heartbeat;
        if (f != null) f.cancel(false);
    }

    /** Thrown when this delivery no longer owns the job; the caller must stop without writing results. */
    public static final class LeaseLostException extends RuntimeException {
        public LeaseLostException(UUID jobId) {
            super("Lease on job " + jobId + " was lost to another worker");
        }
    }
}
