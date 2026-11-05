package com.patchlens.controller;

import com.patchlens.model.ReviewJob;
import com.patchlens.service.JobPublisher;
import com.patchlens.service.ReviewJobService;
import com.patchlens.service.WebhookDeduplicationService;
import com.patchlens.service.WebhookSignatureValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Failure windows between the Redis claim, the job row and the broker publish. */
class WebhookControllerTest {

    private static final String SHA = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    private static final String PAYLOAD = """
            {"action":"synchronize","number":42,
             "pull_request":{"number":42,"html_url":"https://github.com/acme/backend/pull/42","head":{"sha":"%s"}},
             "repository":{"name":"backend","owner":{"login":"acme"}}}
            """.formatted(SHA);

    private WebhookSignatureValidator validator;
    private WebhookDeduplicationService dedup;
    private ReviewJobService jobs;
    private JobPublisher publisher;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        validator = mock(WebhookSignatureValidator.class);
        dedup = mock(WebhookDeduplicationService.class);
        jobs = mock(ReviewJobService.class);
        publisher = mock(JobPublisher.class);
        when(validator.isValid(any(), any())).thenReturn(true);
        when(dedup.claim("acme", "backend", 42, SHA)).thenReturn(true);
        controller = new WebhookController(validator, dedup, jobs, publisher, new ObjectMapper());
    }

    private ResponseEntity<?> post() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/webhooks/github");
        req.setContent(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        return controller.handleGitHubWebhook("sha256=x", "pull_request", req);
    }

    @Test
    void databaseFailureReleasesTheDedupClaimAndAsksGitHubToRetry() throws Exception {
        when(jobs.createOrFind(any(), any(), anyInt(), any(), any())).thenThrow(new RuntimeException("db down"));

        ResponseEntity<?> r = post();

        assertThat(r.getStatusCode().value()).isEqualTo(503);
        verify(dedup).release("acme", "backend", 42, SHA);
        verify(dedup, never()).confirm(any(), any(), anyInt(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void brokerFailureStillAcceptsTheDurableJobForOutboxRecovery() throws Exception {
        ReviewJob job = savedJob();
        when(jobs.createOrFind(any(), any(), anyInt(), any(), any())).thenReturn(job);
        when(publisher.publish(job)).thenReturn(false); // RabbitMQ unavailable

        ResponseEntity<?> r = post();

        assertThat(r.getStatusCode().value()).isEqualTo(202);
        verify(dedup).confirm("acme", "backend", 42, SHA);
        verify(publisher).publish(job);
    }

    @Test
    void alreadyPublishedJobIsNotRepublishedByTheWebhook() throws Exception {
        ReviewJob job = savedJob();
        job.setPublishedAt(java.time.Instant.now());
        when(jobs.createOrFind(any(), any(), anyInt(), any(), any())).thenReturn(job);

        post();

        verifyNoInteractions(publisher);
    }

    /** A job as returned by the repository (id assigned). */
    private static ReviewJob savedJob() throws Exception {
        ReviewJob job = new ReviewJob("acme", "backend", 42, "u", SHA);
        var id = ReviewJob.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(job, java.util.UUID.randomUUID());
        return job;
    }
}
