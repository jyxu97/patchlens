package com.patchlens.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Central names and helpers for PatchLens metrics, recorded on Micrometer's global registry
 * (Spring Boot binds it to the application registry, exposed at /actuator/prometheus and
 * /actuator/metrics). Tags never contain source code, tokens or free-form error text.
 */
public final class PatchLensMetrics {

    public static final String MODEL_LATENCY      = "patchlens.model.latency";
    public static final String MODEL_ERRORS       = "patchlens.model.errors";
    public static final String SANDBOX_DURATION   = "patchlens.sandbox.duration";
    public static final String VALIDATION_OUTCOME = "patchlens.validation.outcomes";
    public static final String JOB_RETRIES        = "patchlens.jobs.retries";
    public static final String JOB_DEAD_LETTERED  = "patchlens.jobs.dead_lettered";
    public static final String JOB_LEASE_LOST     = "patchlens.jobs.lease_lost";
    public static final String JOB_REPUBLISHED    = "patchlens.jobs.republished";
    public static final String PUBLISH_FAILURES   = "patchlens.jobs.publish_failures";
    public static final String COMMENT_DELIVERY   = "patchlens.comments.delivery";

    private PatchLensMetrics() {}

    /** Times a model call; failures are counted by exception type (no message text). */
    public static <T> T timeModel(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(Metrics.globalRegistry);
        String outcome = "success";
        try {
            return call.get();
        } catch (RuntimeException e) {
            outcome = "error";
            Metrics.counter(MODEL_ERRORS, "operation", operation, "exception", e.getClass().getSimpleName()).increment();
            throw e;
        } finally {
            sample.stop(Timer.builder(MODEL_LATENCY).tag("operation", operation).tag("outcome", outcome)
                    .publishPercentileHistogram().register(Metrics.globalRegistry));
        }
    }

    public static void sandboxStep(String step, boolean passed, boolean timedOut, long durationMs) {
        Timer.builder(SANDBOX_DURATION)
                .tag("step", step)
                .tag("result", timedOut ? "timeout" : passed ? "passed" : "failed")
                .register(Metrics.globalRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    public static void validationOutcome(String status, String failureStage) {
        Metrics.counter(VALIDATION_OUTCOME, "status", status, "stage", failureStage == null ? "NONE" : failureStage).increment();
    }

    public static void increment(String name, String... tags) {
        Metrics.counter(name, tags).increment();
    }
}
