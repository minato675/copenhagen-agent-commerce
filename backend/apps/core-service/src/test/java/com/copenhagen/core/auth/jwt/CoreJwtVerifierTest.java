package com.copenhagen.core.auth.jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreJwtVerifierTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private final CoreAuthJwtKeySet keySet = new CoreAuthJwtKeySet("", "shared-kid");
    private final CoreJwtIssuer issuer = new CoreJwtIssuer(keySet, FIXED_CLOCK);
    private final CoreJwtVerifier verifier = new CoreJwtVerifier(keySet, FIXED_CLOCK);

    @Test
    void verify_validToken_returnsSubject() {
        UUID subject = UUID.randomUUID();
        String token = issuer.issue(subject).token();

        Optional<UUID> result = verifier.verify(token);

        assertTrue(result.isPresent());
        assertEquals(subject, result.get());
    }

    @Test
    void verify_expiredToken_rejected() {
        String token = sign(claims("copenhagen-core", "pay-service", FIXED_INSTANT.minusSeconds(3600)));

        assertTrue(verifier.verify(token).isEmpty());
    }

    @Test
    void verify_tokenSignedWithDifferentKey_rejected() throws JOSEException {
        CoreAuthJwtKeySet otherKeySet = new CoreAuthJwtKeySet("", "shared-kid");
        JWTClaimsSet claims = claims("copenhagen-core", "pay-service", FIXED_INSTANT.plusSeconds(1800));
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("shared-kid").build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new ECDSASigner(otherKeySet.signingKey()));

        assertTrue(verifier.verify(jwt.serialize()).isEmpty());
    }

    @Test
    void verify_wrongIssuer_rejected() {
        String token = sign(claims("someone-else", "pay-service", FIXED_INSTANT.plusSeconds(1800)));

        assertTrue(verifier.verify(token).isEmpty());
    }

    @Test
    void verify_wrongAudience_rejected() {
        String token = sign(claims("copenhagen-core", "someone-else", FIXED_INSTANT.plusSeconds(1800)));

        assertTrue(verifier.verify(token).isEmpty());
    }

    @Test
    void verify_blankToken_rejected() {
        assertTrue(verifier.verify("").isEmpty());
        assertTrue(verifier.verify(null).isEmpty());
    }

    private JWTClaimsSet claims(String issuer, String audience, Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .audience(audience)
                .issueTime(Date.from(FIXED_INSTANT))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();
    }

    private String sign(JWTClaimsSet claims) {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keySet.signingKeyId()).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new ECDSASigner(keySet.signingKey()));
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
        return jwt.serialize();
    }
}
