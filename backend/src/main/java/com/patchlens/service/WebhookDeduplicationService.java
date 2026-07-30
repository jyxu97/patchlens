package com.patchlens.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed atomic webhook deduplication.
 *
 * Uses SET NX (set-if-not-exists) so only the first delivery for a given
 * (owner, repo, pullNumber, headSha) creates a review job.  All later
 * deliveries — including GitHub's automatic retries — are suppressed.
 *
 * On Redis failure, claim() fails open so the DB unique constraint on
 * review_jobs acts as a safety net.
 */
@Service
public class WebhookDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeduplicationService.class);
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;

    public WebhookDeduplicationService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Atomically claim the dedup slot for this PR state.
     *
     * @return true  — first delivery; caller should create and enqueue the job.
     *         false — duplicate delivery; caller should return 2xx silently.
     *
     * Fails open on Redis unavailability so the DB unique constraint remains
     * the last line of defence.
     */
    public boolean claim(String owner, String repo, int pullNumber, String headSha) {
        try {
            String key = dedupKey(owner, repo, pullNumber, headSha);
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, "1", DEDUP_TTL);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Redis dedup claim failed, falling back to DB constraint: {}", e.getMessage());
            return true;  // fail open
        }
    }

    static String dedupKey(String owner, String repo, int pullNumber, String headSha) {
        return "patchlens:dedup:" + owner + ":" + repo + ":" + pullNumber + ":" + headSha;
    }
}
