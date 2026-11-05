package com.patchlens.controller;

import com.patchlens.dto.WebhookPrPayload;
import com.patchlens.model.JobStatus;
import com.patchlens.model.ReviewJob;
import com.patchlens.service.JobPublisher;
import com.patchlens.service.ReviewJobService;
import com.patchlens.service.WebhookDeduplicationService;
import com.patchlens.service.WebhookSignatureValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    // Only these actions trigger an analysis
    private static final Set<String> ANALYZED_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final WebhookSignatureValidator signatureValidator;
    private final WebhookDeduplicationService deduplicationService;
    private final ReviewJobService reviewJobService;
    private final JobPublisher jobPublisher;
    private final ObjectMapper objectMapper;

    public WebhookController(WebhookSignatureValidator signatureValidator,
                             WebhookDeduplicationService deduplicationService,
                             ReviewJobService reviewJobService,
                             JobPublisher jobPublisher,
                             ObjectMapper objectMapper) {
        this.signatureValidator = signatureValidator;
        this.deduplicationService = deduplicationService;
        this.reviewJobService = reviewJobService;
        this.jobPublisher = jobPublisher;
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
        // head.sha uniquely identifies the commit; used as the idempotency key
        String headSha = webhookPayload.pullRequest().head() != null
                ? webhookPayload.pullRequest().head().sha() : "unknown";

        // Primary dedup gate: Redis SET NX (atomic; provisional TTL until the job is committed).
        // Falls open if Redis is unavailable; DB unique constraint is the safety net.
        if (!deduplicationService.claim(owner, repo, pullNumber, headSha)) {
            log.info("Duplicate webhook suppressed for {}/{} PR#{} sha={}",
                    owner, repo, pullNumber, headSha);
            return ResponseEntity.accepted().body(Map.of(
                    "status", "accepted",
                    "duplicate", true
            ));
        }

        // Idempotency: reuse existing job for the same PR commit (concurrent-safe).
        ReviewJob job;
        try {
            job = reviewJobService.createOrFind(owner, repo, pullNumber, prUrl, headSha);
        } catch (RuntimeException e) {
            // Nothing durable was recorded: release the claim and let GitHub redeliver (5xx).
            deduplicationService.release(owner, repo, pullNumber, headSha);
            log.error("Could not create review job for {}/{} PR#{}: {}", owner, repo, pullNumber, e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "JOB_NOT_CREATED",
                    "message", "Review job could not be persisted; retry the delivery."
            ));
        }
        deduplicationService.confirm(owner, repo, pullNumber, headSha);

        // Outbox: the job row is committed; publish now if it never was. A broker failure leaves
        // publishedAt null and ReliabilityScheduler republishes — the accepted job is not lost.
        if (job.getStatus() == JobStatus.PENDING && job.getPublishedAt() == null) {
            jobPublisher.publish(job);
        }

        return ResponseEntity.accepted().body(Map.of(
                "jobId", job.getId(),
                "status", job.getStatus()
        ));
    }
}
