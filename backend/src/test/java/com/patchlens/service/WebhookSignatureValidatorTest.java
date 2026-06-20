package com.patchlens.service;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureValidatorTest {

    private static final String SECRET  = "test-secret";
    private static final byte[] PAYLOAD = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    // ------------------------------------------------------------------ helpers

    /** Produces the same "sha256=<hex>" string that GitHub sends in the header. */
    private static String sign(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload);
        StringBuilder sb = new StringBuilder("sha256=");
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static WebhookSignatureValidator validatorWith(String secret) {
        return new WebhookSignatureValidator(secret);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void validSignatureIsAccepted() throws Exception {
        String signature = sign(SECRET, PAYLOAD);
        assertThat(validatorWith(SECRET).isValid(PAYLOAD, signature)).isTrue();
    }

    @Test
    void wrongSignatureIsRejected() throws Exception {
        String signature = sign(SECRET, PAYLOAD);
        byte[] differentPayload = "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(validatorWith(SECRET).isValid(differentPayload, signature)).isFalse();
    }

    @Test
    void wrongSecretIsRejected() throws Exception {
        String signatureWithOtherSecret = sign("wrong-secret", PAYLOAD);
        assertThat(validatorWith(SECRET).isValid(PAYLOAD, signatureWithOtherSecret)).isFalse();
    }

    @Test
    void nullSignatureIsRejected() {
        assertThat(validatorWith(SECRET).isValid(PAYLOAD, null)).isFalse();
    }

    @Test
    void signatureWithoutPrefixIsRejected() throws Exception {
        // GitHub always sends "sha256=<hex>"; raw hex without prefix must be rejected
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(PAYLOAD);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));

        assertThat(validatorWith(SECRET).isValid(PAYLOAD, hex.toString())).isFalse();
    }

    @Test
    void emptySecretSkipsValidation() {
        // Secret not configured → always pass (dev/test mode)
        assertThat(validatorWith("").isValid(PAYLOAD, null)).isTrue();
        assertThat(validatorWith("").isValid(PAYLOAD, "sha256=garbage")).isTrue();
    }

    @Test
    void blankSecretSkipsValidation() {
        assertThat(validatorWith("   ").isValid(PAYLOAD, null)).isTrue();
    }

    @Test
    void emptyPayloadWithCorrectSignature() throws Exception {
        byte[] empty = new byte[0];
        String signature = sign(SECRET, empty);
        assertThat(validatorWith(SECRET).isValid(empty, signature)).isTrue();
    }
}
