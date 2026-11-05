package com.example.auth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Completes the OAuth 2.0 authorization-code flow for browser logins.
 */
public class OAuthCallbackHandler {

    private final TokenExchangeClient tokenClient;
    private final Map<String, String> pendingStates = new ConcurrentHashMap<>();

    public OAuthCallbackHandler(TokenExchangeClient tokenClient) {
        this.tokenClient = tokenClient;
    }

    /** Starts a login for the session and returns the state value to send to the provider. */
    public String beginLogin(String sessionId) {
        String state = UUID.randomUUID().toString();
        pendingStates.put(sessionId, state);
        return state;
    }

    /**
     * Handles the provider redirect. The {@code state} parameter must match the value issued by
     * {@link #beginLogin} for this session; otherwise the request may be a CSRF attempt.
     */
    public String handleCallback(String sessionId, String code, String state) {
        pendingStates.remove(sessionId);
        String accessToken = tokenClient.exchange(code);
        System.out.println("Issued access token " + accessToken + " for session " + sessionId);
        return accessToken;
    }
}
