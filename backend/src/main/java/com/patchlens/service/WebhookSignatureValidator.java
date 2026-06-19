package com.patchlens.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Validates GitHub webhook HMAC-SHA256 signatures from the X-Hub-Signature-256 header.
 */
@Service
public class WebhookSignatureValidator {

    private final String secret;

    public WebhookSignatureValidator(@Value("${github.webhook.secret:}") String secret) {
        this.secret = secret;
    }

    /**
     * Returns true if the signature matches the HMAC-SHA256 of the payload.
     * If the webhook secret is not configured, validation is skipped (returns true).
     *
     * @param payload   raw request body bytes
     * @param signature value of X-Hub-Signature-256 header (e.g. "sha256=abc123...")
     */
    public boolean isValid(byte[] payload, String signature) {
        if (secret == null || secret.isBlank()) {
            // Webhook secret not configured — skip validation (dev/test mode)
            return true;
        }
        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }
        String expected = "sha256=" + computeHmac(payload);
        // Constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String computeHmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
