package com.patchlens.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeduplicationServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private WebhookDeduplicationService service;

    private static final String OWNER  = "acme";
    private static final String REPO   = "backend";
    private static final int    PR_NUM = 42;
    private static final String SHA    = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new WebhookDeduplicationService(redisTemplate);
    }

    @Test
    void firstDelivery_returnsTrue() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertThat(service.claim(OWNER, REPO, PR_NUM, SHA)).isTrue();
    }

    @Test
    void duplicateDelivery_returnsFalse() {
        // Slot already claimed — Redis returns false (key exists)
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        assertThat(service.claim(OWNER, REPO, PR_NUM, SHA)).isFalse();
    }

    @Test
    void redisUnavailable_failsOpen() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // Must fail open so the DB constraint remains the safety net
        assertThat(service.claim(OWNER, REPO, PR_NUM, SHA)).isTrue();
    }

    @Test
    void differentHeadSha_claimsIndependently() {
        String sha2 = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
        String key1 = WebhookDeduplicationService.dedupKey(OWNER, REPO, PR_NUM, SHA);
        String key2 = WebhookDeduplicationService.dedupKey(OWNER, REPO, PR_NUM, sha2);

        when(valueOps.setIfAbsent(eq(key1), eq("1"), any(Duration.class))).thenReturn(false);
        when(valueOps.setIfAbsent(eq(key2), eq("1"), any(Duration.class))).thenReturn(true);

        assertThat(service.claim(OWNER, REPO, PR_NUM, SHA)).isFalse();
        assertThat(service.claim(OWNER, REPO, PR_NUM, sha2)).isTrue();
    }

    @Test
    void dedupKey_hasExpectedFormat() {
        String key = WebhookDeduplicationService.dedupKey(OWNER, REPO, PR_NUM, SHA);
        assertThat(key).isEqualTo("patchlens:dedup:acme:backend:42:" + SHA);
    }
}
