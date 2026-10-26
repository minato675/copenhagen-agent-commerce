package com.copenhagen.pay.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtBearerTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final String ISSUER = "https://core.example.com";
    private static final String AUDIENCE = "pay-service";
    private static final String KEY_ID = "core-access-key-1";

    private ECKey signingKey;
    private JwtBearerTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_256)
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.ES256)
                .generate();
        verifier = new JwtBearerTokenVerifier(
                new PayJwtProperties(
                        true,
                        ISSUER,
                        AUDIENCE,
                        JSONObjectUtils.toJSONString(
                                new JWKSet(signingKey.toPublicJWK()).toJSONObject()),
                        0),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void verifiesCoreJwtAndUsesSubjectAsUserId() throws Exception {
        String token = token(ISSUER, "user-001", AUDIENCE, NOW.plusSeconds(300));

        Optional<PayPrincipal> principal = verifier.verify(token);

        assertTrue(principal.isPresent());
        assertEquals(PayPrincipal.Type.USER, principal.orElseThrow().type());
        assertEquals("user-001", principal.orElseThrow().subject());
    }

    @Test
    void rejectsTokenWithWrongAudience() throws Exception {
        String token = token(ISSUER, "user-001", "core-service", NOW.plusSeconds(300));

        assertTrue(verifier.verify(token).isEmpty());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = token(ISSUER, "user-001", AUDIENCE, NOW.minusSeconds(1));

        assertTrue(verifier.verify(token).isEmpty());
    }

    @Test
    void rejectsTokenWithoutSubject() throws Exception {
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .type(JOSEObjectType.JWT)
                        .keyID(KEY_ID)
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .audience(AUDIENCE)
                        .expirationTime(Date.from(NOW.plusSeconds(300)))
                        .build());
        token.sign(new ECDSASigner(signingKey));

        assertTrue(verifier.verify(token.serialize()).isEmpty());
    }

    private String token(
            String issuer,
            String subject,
            String audience,
            Instant expiration) throws Exception {
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .type(JOSEObjectType.JWT)
                        .keyID(KEY_ID)
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(subject)
                        .audience(audience)
                        .expirationTime(Date.from(expiration))
                        .build());
        token.sign(new ECDSASigner(signingKey));
        return token.serialize();
    }
}
