package com.patchlens.controller;

import com.patchlens.config.RabbitMQConfig;
import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.dto.WebhookPrPayload;
import com.patchlens.model.ReviewJob;
import com.patchlens.service.ReviewJobService;
import com.patchlens.service.WebhookSignatureValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

/**
 * Receives GitHub pull_request webhook events.
 * POST /api/webhooks/github
 *
 * IMPORTANT: Raw bytes are read manually (not via @RequestBody) so that
 * HMAC-SHA256 validation receives the unmodified payload.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    // Only these actions trigger an analysis
    private static final Set<String> ANALYZED_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final WebhookSignatureValidator signatureValidator;
    private final ReviewJobService reviewJobService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public WebhookController(WebhookSignatureValidator signatureValidator,
                             ReviewJobService reviewJobService,
                             RabbitTemplate rabbitTemplate,
                             ObjectMapper objectMapper) {
        this.signatureValidator = signatureValidator;
        this.reviewJobService = reviewJobService;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/github")
    public ResponseEntity<?> handleGitHubWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            HttpServletRequest request) throws Exception {

        // Read raw bytes BEFORE any deserialization to preserve HMAC-integrity
        byte[] payload = request.getInputStream().readAllBytes();

        // Validate HMAC signature
        if (!signatureValidator.isValid(payload, signature)) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "INVALID_SIGNATURE",
                    "message", "X-Hub-Signature-256 validation failed."
            ));
        }

        // Only handle pull_request events
        if (!"pull_request".equals(eventType)) {
            return ResponseEntity.ok(Map.of("message", "Event type ignored: " + eventType));
        }

        // Deserialize after signature validation
        WebhookPrPayload webhookPayload = objectMapper.readValue(payload, WebhookPrPayload.class);

        // Only handle the actions that indicate new code to review
        if (!ANALYZED_ACTIONS.contains(webhookPayload.action())) {
            return ResponseEntity.ok(Map.of(
                    "message", "Action ignored: " + webhookPayload.action()
            ));
        }

        // Extract PR details
        String owner = webhookPayload.repository().owner().login();
        String repo = webhookPayload.repository().name();
        int pullNumber = webhookPayload.pullRequest().number();
        String prUrl = webhookPayload.pullRequest().htmlUrl();

        // Idempotency: reuse existing PENDING/PROCESSING job for the same PR
        ReviewJob job = reviewJobService.createOrFind(owner, repo, pullNumber, prUrl);

        // Publish message to RabbitMQ (only if job is still PENDING — don't re-queue PROCESSING jobs)
        switch (job.getStatus()) {
            case PENDING -> {
                ReviewJobMessage message = new ReviewJobMessage(
                        job.getId(), owner, repo, pullNumber, prUrl);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.REVIEWS_EXCHANGE,
                        RabbitMQConfig.REVIEW_JOBS_ROUTING_KEY,
                        message);
            }
            case PROCESSING -> {
                // Already being worked on — return existing job ID
            }
            default -> {
                // Completed/failed jobs are not re-submitted here
            }
        }

        return ResponseEntity.accepted().body(Map.of(
                "jobId", job.getId(),
                "status", job.getStatus()
        ));
    }
}
