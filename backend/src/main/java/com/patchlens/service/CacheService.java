package com.patchlens.service;

import com.patchlens.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private static final Duration TTL_GITHUB_PR = Duration.ofHours(24);
    private static final Duration TTL_SAMPLE_PR = Duration.ofDays(7);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // --- cache key builders ---

    /** Key format: patchlens:review:{owner}:{repo}:{prNumber}:{diffHash} */
    public String reviewKey(String owner, String repo, int prNumber, String diffHash) {
        return "patchlens:review:" + owner + ":" + repo + ":" + prNumber + ":" + diffHash;
    }

    /** Key format: patchlens:sample:{sampleId}:{diffHash} */
    public String sampleKey(String sampleId, String diffHash) {
        return "patchlens:sample:" + sampleId + ":" + diffHash;
    }

    // --- get and put ---

    /**
     * Returns the cached ReviewResult for the given key, or empty if not cached.
     * If Redis is unavailable, logs a warning and returns empty (cache miss).
     */
    public Optional<ReviewResult> get(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, ReviewResult.class));
        } catch (Exception e) {
            log.warn("Cache get failed for key '{}': {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores a ReviewResult in Redis with the given TTL.
     * If Redis is unavailable, logs a warning and continues silently.
     */
    public void put(String key, ReviewResult result, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("Cache put failed for key '{}': {}", key, e.getMessage());
        }
    }

    public Duration ttlForGitHubPr() { return TTL_GITHUB_PR; }
    public Duration ttlForSamplePr() { return TTL_SAMPLE_PR; }
}
