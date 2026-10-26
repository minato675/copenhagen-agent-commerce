package com.copenhagen.core.auth.jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CoreJwtIssuerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private final CoreAuthJwtKeySet keySet = new CoreAuthJwtKeySet("", "");
    private final CoreJwtIssuer issuer = new CoreJwtIssuer(keySet, FIXED_CLOCK);

    @Test
    void issue_setsExpectedClaimsAndExpiry() throws Exception {
        UUID subject = UUID.randomUUID();

        IssuedAccessToken token = issuer.issue(subject);

        assertEquals(1800L, token.expiresInSeconds());

        JWTClaimsSet claims = SignedJWT.parse(token.token()).getJWTClaimsSet();
        assertEquals("copenhagen-core", claims.getIssuer());
        assertEquals(subject.toString(), claims.getSubject());
        assertEquals("pay-service", claims.getAudience().get(0));
        assertEquals(FIXED_INSTANT, claims.getIssueTime().toInstant());
        assertEquals(FIXED_INSTANT.plusSeconds(1800), claims.getExpirationTime().toInstant());
        assertNotNull(claims.getJWTID());
    }

    @Test
    void issue_generatesUniqueJwtIdPerCall() throws Exception {
        UUID subject = UUID.randomUUID();

        String jti1 = SignedJWT.parse(issuer.issue(subject).token()).getJWTClaimsSet().getJWTID();
        String jti2 = SignedJWT.parse(issuer.issue(subject).token()).getJWTClaimsSet().getJWTID();

        assertNotEquals(jti1, jti2);
    }
}
