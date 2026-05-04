package com.patchlens.service;

import com.patchlens.model.ReviewResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new CacheService(redisTemplate, new ObjectMapper());
    }

    // --- key format ---

    @Test
    void reviewKeyShouldFollowExpectedFormat() {
        String key = cacheService.reviewKey("octocat", "hello-world", 42, "abc123");
        assertThat(key).isEqualTo("patchlens:review:octocat:hello-world:42:abc123");
    }

    @Test
    void sampleKeyShouldFollowExpectedFormat() {
        String key = cacheService.sampleKey("redis-session-cache", "def456");
        assertThat(key).isEqualTo("patchlens:sample:redis-session-cache:def456");
    }

    // --- TTL constants ---

    @Test
    void ttlForGitHubPrShouldBe24Hours() {
        assertThat(cacheService.ttlForGitHubPr()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void ttlForSamplePrShouldBe7Days() {
        assertThat(cacheService.ttlForSamplePr()).isEqualTo(Duration.ofDays(7));
    }

    // --- get ---

    @Test
    void getShouldReturnEmptyOnCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("missing-key")).thenReturn(null);

        assertThat(cacheService.get("missing-key")).isEmpty();
    }

    @Test
    void getShouldReturnEmptyAndNotThrowWhenRedisIsDown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("some-key")).thenThrow(new RuntimeException("connection refused"));

        assertThat(cacheService.get("some-key")).isEmpty();
    }

    @Test
    void getShouldDeserializeCachedResult() throws Exception {
        var objectMapper = new ObjectMapper();
        var expected = new ReviewResult(
                new ReviewResult.Summary("PR Title", "overview", List.of("change")),
                new ReviewResult.RiskAssessment("low", List.of()),
                List.of("write tests"),
                List.of("review logic")
        );
        String json = objectMapper.writeValueAsString(expected);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("cached-key")).thenReturn(json);

        Optional<ReviewResult> result = cacheService.get("cached-key");

        assertThat(result).isPresent();
        assertThat(result.get().summary().title()).isEqualTo("PR Title");
    }
}
