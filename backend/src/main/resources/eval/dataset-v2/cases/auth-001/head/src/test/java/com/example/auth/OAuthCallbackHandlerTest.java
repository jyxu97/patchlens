package com.example.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OAuthCallbackHandlerTest {

    @Test
    void validCallbackReturnsAccessToken() {
        OAuthCallbackHandler handler = new OAuthCallbackHandler(code -> "token-for-" + code);
        String state = handler.beginLogin("s1");
        assertEquals("token-for-abc", handler.handleCallback("s1", "abc", state));
    }
}
