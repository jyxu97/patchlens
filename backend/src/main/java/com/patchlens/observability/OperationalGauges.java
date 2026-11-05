package com.patchlens.observability;

import com.patchlens.config.RabbitMQConfig;
import com.patchlens.model.CommentStatus;
import com.patchlens.model.JobStatus;
import com.patchlens.repository.ReviewJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Point-in-time operational gauges: queue depth (main queue and DLQ), age of the oldest
 * PENDING job, and counts of in-progress jobs and undelivered comments. Each gauge is computed
 * on scrape and reports NaN (rather than failing the scrape) if its source is unavailable.
 */
@Component
public class OperationalGauges {

    public OperationalGauges(MeterRegistry registry, ObjectProvider<AmqpAdmin> amqpAdmin, ReviewJobRepository jobs) {
        Gauge.builder("patchlens.queue.depth", () -> queueDepth(amqpAdmin, RabbitMQConfig.REVIEW_JOBS_QUEUE))
                .tag("queue", RabbitMQConfig.REVIEW_JOBS_QUEUE).description("Messages ready in the queue").register(registry);
        Gauge.builder("patchlens.queue.depth", () -> queueDepth(amqpAdmin, RabbitMQConfig.REVIEW_JOBS_DLQ))
                .tag("queue", RabbitMQConfig.REVIEW_JOBS_DLQ).description("Messages in the dead-letter queue").register(registry);

        Gauge.builder("patchlens.jobs.oldest_pending_age", () -> safe(() -> {
                    Instant oldest = jobs.oldestCreatedAtWithStatus(JobStatus.PENDING);
                    return oldest == null ? 0.0 : (double) Duration.between(oldest, Instant.now()).toSeconds();
                })).baseUnit("seconds").description("Age of the oldest job not yet picked up").register(registry);

        for (JobStatus s : new JobStatus[]{JobStatus.PROCESSING, JobStatus.GENERATING_FINDINGS,
                JobStatus.GENERATING_PATCHES, JobStatus.VALIDATING_PATCHES, JobStatus.FAILED}) {
            Gauge.builder("patchlens.jobs.by_status", () -> safe(() -> (double) jobs.countByStatus(s)))
                    .tag("status", s.name()).register(registry);
        }
        for (CommentStatus s : new CommentStatus[]{CommentStatus.PENDING, CommentStatus.FAILED_RETRYABLE, CommentStatus.FAILED_PERMANENT}) {
            Gauge.builder("patchlens.comments.by_status", () -> safe(() -> (double) jobs.countByCommentStatus(s)))
                    .tag("status", s.name()).register(registry);
        }
    }

    private static double queueDepth(ObjectProvider<AmqpAdmin> admin, String queue) {
        return safe(() -> {
            AmqpAdmin a = admin.getIfAvailable();
            if (a == null) return Double.NaN;
            Properties p = a.getQueueProperties(queue);
            Object count = p == null ? null : p.get("QUEUE_MESSAGE_COUNT");
            return count instanceof Number n ? n.doubleValue() : Double.NaN;
        });
    }

    private static double safe(Supplier<Double> s) {
        try { return s.get(); } catch (RuntimeException e) { return Double.NaN; }
    }
}
