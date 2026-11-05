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
    /** TTL once the job row is durably committed. */
    static final Duration DEDUP_TTL = Duration.ofHours(24);
    /**
     * Provisional TTL between the claim and the DB commit. If the process dies in that window the
     * key expires quickly instead of suppressing GitHub's redelivery of the event for 24 hours.
     */
    static final Duration PROVISIONAL_TTL = Duration.ofMinutes(5);

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
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, "1", PROVISIONAL_TTL);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Redis dedup claim failed, falling back to DB constraint: {}", e.getMessage());
            return true;  // fail open
        }
    }

    /** Extends the claim to the full TTL after the job has been committed. Best effort. */
    public void confirm(String owner, String repo, int pullNumber, String headSha) {
        try {
            redisTemplate.expire(dedupKey(owner, repo, pullNumber, headSha), DEDUP_TTL);
        } catch (Exception e) {
            log.warn("Redis dedup confirm failed (DB constraint still deduplicates): {}", e.getMessage());
        }
    }

    /**
     * Releases a claim whose job could not be created, so a redelivery of the same event is
     * processed instead of being suppressed as a duplicate. Best effort.
     */
    public void release(String owner, String repo, int pullNumber, String headSha) {
        try {
            redisTemplate.delete(dedupKey(owner, repo, pullNumber, headSha));
        } catch (Exception e) {
            log.warn("Redis dedup release failed; key expires in {}: {}", PROVISIONAL_TTL, e.getMessage());
        }
    }

    static String dedupKey(String owner, String repo, int pullNumber, String headSha) {
        return "patchlens:dedup:" + owner + ":" + repo + ":" + pullNumber + ":" + headSha;
    }
}
