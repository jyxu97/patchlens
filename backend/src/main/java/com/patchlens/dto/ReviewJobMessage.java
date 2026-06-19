package com.patchlens.dto;

import java.util.UUID;

/**
 * AMQP message body for the review.jobs queue.
 * Serialized as JSON by Jackson2JsonMessageConverter.
 */
public record ReviewJobMessage(
        UUID jobId,
        String owner,
        String repo,
        int pullNumber,
        String pullRequestUrl
) {}
