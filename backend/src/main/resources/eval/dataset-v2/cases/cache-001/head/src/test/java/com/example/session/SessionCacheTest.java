package com.example.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCacheTest {

    @Test
    void unknownSessionIsEmpty() {
        SessionCache cache = new SessionCache(Clock.systemUTC(), Duration.ofMinutes(30));
        assertTrue(cache.get("nope").isEmpty());
    }

    @Test
    void invalidatedSessionIsGone() {
        SessionCache cache = new SessionCache(Clock.systemUTC(), Duration.ofMinutes(30));
        Session s = cache.create("alice");
        cache.invalidate(s.id());
        assertTrue(cache.get(s.id()).isEmpty());
    }
}
