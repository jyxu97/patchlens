package com.patchlens.service;

import com.patchlens.dto.JobStatusResponse;
import com.patchlens.model.ReviewJob;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory SSE hub.
 * Maps jobId → SseEmitter so that job state transitions can push events to connected clients.
 */
@Service
public class JobStatusEmitter {

    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * Registers an SseEmitter for the given job and returns it.
     * Removes the emitter on completion/timeout/error.
     */
    public SseEmitter subscribe(UUID jobId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout
        emitters.put(jobId, emitter);

        Runnable cleanup = () -> emitters.remove(jobId);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    /**
     * Sends the current job state to the subscribed client (if any).
     * Silently removes disconnected emitters on IOException.
     */
    public void emit(ReviewJob job) {
        SseEmitter emitter = emitters.get(job.getId());
        if (emitter == null) {
            return;
        }

        JobStatusResponse payload = new JobStatusResponse(
                job.getId(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getResultJson(),
                job.getErrorMessage()
        );

        try {
            emitter.send(SseEmitter.event()
                    .name("job-status")
                    .data(payload));

            // Auto-complete the SSE stream when the job reaches a terminal state
            if (isTerminal(job)) {
                emitter.complete();
                emitters.remove(job.getId());
            }
        } catch (IOException e) {
            // Client disconnected
            emitter.completeWithError(e);
            emitters.remove(job.getId());
        }
    }

    private boolean isTerminal(ReviewJob job) {
        return switch (job.getStatus()) {
            case COMPLETED, FAILED, DEAD_LETTER -> true;
            default -> false;
        };
    }
}
