package com.example.auth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OAuthCallbackHandlerOracleTest {

    @Test
    void mismatchedStateIsRejectedWithoutExchangingTheCode() {
        AtomicInteger exchanges = new AtomicInteger();
        OAuthCallbackHandler handler = new OAuthCallbackHandler(code -> { exchanges.incrementAndGet(); return "t"; });
        handler.beginLogin("s1");
        assertThrows(RuntimeException.class, () -> handler.handleCallback("s1", "abc", "attacker-state"));
        assertEquals(0, exchanges.get());
    }

    @Test
    void callbackWithoutLoginIsRejected() {
        OAuthCallbackHandler handler = new OAuthCallbackHandler(code -> "t");
        assertThrows(RuntimeException.class, () -> handler.handleCallback("unknown", "abc", "any"));
    }

    @Test
    void validStateStillWorks() {
        OAuthCallbackHandler handler = new OAuthCallbackHandler(code -> "token-" + code);
        String state = handler.beginLogin("s1");
        assertEquals("token-abc", handler.handleCallback("s1", "abc", state));
    }
}
