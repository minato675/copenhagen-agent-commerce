package com.copenhagen.core.orderwebhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.ucp.CoreUcpProperties;
import com.nimbusds.jose.crypto.impl.ECDSA;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderWebhookSignatureTest {

    static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private final byte[] payload = "{\"merchant_id\":\"merchant-a\"}".getBytes(StandardCharsets.UTF_8);
    private final OrderWebhookSignature verifier = new OrderWebhookSignature(
            new CoreUcpProperties("http://core-service:8080", Duration.ofMinutes(5)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void merchantSignature_acceptsOriginalBytesAndRejectsTamperingWrongKeyAndExpiredSignatures() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("merchant-key").generate();
        HttpHeaders headers = signed(payload, key, NOW);
        assertEquals(headers.getFirst("Content-Digest"), verifier.verify(payload, headers, List.of(key.toPublicJWK())));
        assertThrows(CoreException.class, () -> verifier.verify("{}".getBytes(StandardCharsets.UTF_8),
                headers, List.of(key.toPublicJWK())));
        ECKey other = new ECKeyGenerator(Curve.P_256).keyID("merchant-key").generate();
        assertThrows(CoreException.class, () -> verifier.verify(payload, headers, List.of(other.toPublicJWK())));
        for (Instant created : List.of(NOW.minusSeconds(301), NOW.plusSeconds(301))) {
            assertThrows(CoreException.class, () -> verifier.verify(payload, signed(payload, key, created),
                    List.of(key.toPublicJWK())));
        }
        headers.set("Content-Type", "text/plain");
        assertThrows(CoreException.class, () -> verifier.verify(payload, headers, List.of(key.toPublicJWK())));
    }

    @Test
    void missingOrMalformedSignature_isRejected() {
        for (String input : List.of("", "sig1=();created=99999999999999999999;keyid=\"x\"", "sig1=()")) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Signature-Input", input);
            assertThrows(CoreException.class, () -> verifier.verify(payload, headers, List.of()));
        }
    }

    static HttpHeaders signed(byte[] payload, ECKey key, Instant created) throws Exception {
        String digest = "sha-256=:" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(payload)) + ":";
        String params = "(\"@method\" \"@authority\" \"@path\" \"content-digest\" \"content-type\");created="
                + created.getEpochSecond() + ";keyid=\"" + key.getKeyID() + "\"";
        String base = "\"@method\": POST\n\"@authority\": core-service:8080\n"
                + "\"@path\": /webhooks/v1/ucp/orders\n\"content-digest\": " + digest
                + "\n\"content-type\": application/json\n\"@signature-params\": " + params;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.toECPrivateKey());
        signer.update(base.getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Content-Digest", digest);
        headers.set("Signature-Input", "sig1=" + params);
        headers.set("Signature", "sig1=:" + Base64.getEncoder().encodeToString(
                ECDSA.transcodeSignatureToConcat(signer.sign(), 64)) + ":");
        headers.set("Webhook-Id", "event-1");
        return headers;
    }
}
