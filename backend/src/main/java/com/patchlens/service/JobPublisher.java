package com.patchlens.service;

import com.patchlens.config.RabbitMQConfig;
import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.model.ReviewJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes review jobs to RabbitMQ using the job row as a transactional outbox.
 *
 * <p>The job is committed to the database <em>before</em> publishing. If the broker is
 * unavailable, publishing fails, {@code publishedAt} stays null, and
 * {@link ReliabilityScheduler} republishes later — an accepted job is never lost. Publishing
 * more than once is safe: workers claim jobs atomically and ignore duplicates.
 */
@Service
public class JobPublisher {

    private static final Logger log = LoggerFactory.getLogger(JobPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ReviewJobService jobs;

    public JobPublisher(RabbitTemplate rabbitTemplate, ReviewJobService jobs) {
        this.rabbitTemplate = rabbitTemplate;
        this.jobs = jobs;
    }

    /** @return true if the message was handed to the broker */
    public boolean publish(ReviewJob job) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.REVIEWS_EXCHANGE, RabbitMQConfig.REVIEW_JOBS_ROUTING_KEY,
                    new ReviewJobMessage(job.getId(), job.getRepositoryOwner(), job.getRepositoryName(),
                            job.getPullRequestNumber(), job.getPullRequestUrl()));
        } catch (RuntimeException e) {
            log.warn("Publishing job {} failed — left in outbox for recovery: {}", job.getId(), e.getMessage());
            return false;
        }
        try {
            jobs.markPublished(job.getId());
        } catch (RuntimeException e) {
            // Message is out; worst case the scheduler publishes a harmless duplicate.
            log.warn("Job {} published but publishedAt not recorded: {}", job.getId(), e.getMessage());
        }
        return true;
    }
}
