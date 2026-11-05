package com.example.auth;

/** Exchanges an OAuth authorization code for an access token at the identity provider. */
public interface TokenExchangeClient {
    String exchange(String authorizationCode);
}
