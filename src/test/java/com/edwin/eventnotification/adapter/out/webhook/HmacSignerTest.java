package com.edwin.eventnotification.adapter.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class HmacSignerTest {

    private final HmacSigner hmacSigner = new HmacSigner();

    @Test
    void matchesAnIndependentlyComputedHmacSha256Hex() throws Exception {
        String secret = "secret";
        String timestamp = "1700000000";
        String body = "hello";

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expectedBytes = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        String expectedHex = HexFormat.of().formatHex(expectedBytes);

        assertThat(hmacSigner.sign(secret, timestamp, body)).isEqualTo(expectedHex);
    }

    @Test
    void isDeterministicForSameInputs() {
        String first = hmacSigner.sign("secret", "1700000000", "hello");
        String second = hmacSigner.sign("secret", "1700000000", "hello");

        assertThat(first).isEqualTo(second);
        assertThat(first).matches("[0-9a-f]{64}");
    }

    @Test
    void differsWhenSecretDiffers() {
        String first = hmacSigner.sign("secret-a", "1700000000", "hello");
        String second = hmacSigner.sign("secret-b", "1700000000", "hello");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void differsWhenBodyDiffers() {
        String first = hmacSigner.sign("secret", "1700000000", "hello");
        String second = hmacSigner.sign("secret", "1700000000", "world");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void differsWhenTimestampDiffers() {
        String first = hmacSigner.sign("secret", "1700000000", "hello");
        String second = hmacSigner.sign("secret", "1700000001", "hello");

        assertThat(first).isNotEqualTo(second);
    }
}
