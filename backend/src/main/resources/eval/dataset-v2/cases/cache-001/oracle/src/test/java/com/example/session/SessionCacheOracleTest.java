package com.example.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class SessionCacheOracleTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2025-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void freshSessionIsReturned() {
        MutableClock clock = new MutableClock();
        SessionCache cache = new SessionCache(clock, Duration.ofMinutes(30));
        Session s = cache.create("alice");
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertEquals(s, cache.get(s.id()).orElseThrow());
    }

    @Test
    void expiredSessionIsNotReturned() {
        MutableClock clock = new MutableClock();
        SessionCache cache = new SessionCache(clock, Duration.ofMinutes(30));
        Session s = cache.create("alice");
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertTrue(cache.get(s.id()).isEmpty());
    }
}
