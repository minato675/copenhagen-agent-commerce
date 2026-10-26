package com.copenhagen.core.agent.jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp-server {@code JwksMcpTokenVerifier}(이슈 S15P21A207-400)가 요구하는 클레임 계약을 코드로
 * 고정한다(설계서 §2.2) — 서명 alg, {@code sub}/{@code connection_id}/{@code scope}(공백구분),
 * TTL ≤300초.
 */
class AgentJwtIssuerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String ISSUER = "https://core.example.com";
    private static final String MCP_AUDIENCE = "https://core.example.com/mcp";

    private final AgentJwtKeySet keySet = new AgentJwtKeySet("", "test-key");
    private final AgentJwtIssuer issuer =
            new AgentJwtIssuer(keySet, new AgentOauthProperties(ISSUER, MCP_AUDIENCE), FIXED_CLOCK);

    @Test
    @DisplayName("Agent Access Token은 JwksMcpTokenVerifier가 요구하는 모든 클레임을 만족한다")
    void issueAgentAccessToken_satisfiesMcpVerifierContract() throws Exception {
        UUID userPublicId = UUID.randomUUID();
        String connectionPublicId = UUID.randomUUID().toString();

        IssuedAgentToken token = issuer.issueAgentAccessToken(
                userPublicId, connectionPublicId, Set.of("catalog:read", "cart:read"));

        assertEquals(300L, token.expiresInSeconds());
        SignedJWT signedJwt = SignedJWT.parse(token.token());
        assertEquals(JWSAlgorithm.ES256, signedJwt.getHeader().getAlgorithm());
        assertNotNull(signedJwt.getHeader().getKeyID());
        assertNull(signedJwt.getHeader().getCriticalParams());

        JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
        assertEquals(ISSUER, claims.getIssuer());
        assertTrue(claims.getAudience().contains(MCP_AUDIENCE));
        assertEquals(userPublicId.toString(), claims.getSubject());
        assertEquals(connectionPublicId, claims.getStringClaim("connection_id"));
        assertTrue(Set.of(claims.getStringClaim("scope").split(" ")).equals(Set.of("catalog:read", "cart:read")));
        assertEquals(300, claims.getExpirationTime().toInstant().getEpochSecond()
                - claims.getIssueTime().toInstant().getEpochSecond());
    }

    @Test
    @DisplayName("Service JWT는 connection_id 클레임이 없다(사용자 위임 토큰이 아니므로)")
    void issueServiceToken_hasNoConnectionIdClaim() throws Exception {
        IssuedAgentToken token = issuer.issueServiceToken("copenhagen-mcp", Set.of("catalog:read"));

        JWTClaimsSet claims = SignedJWT.parse(token.token()).getJWTClaimsSet();
        assertEquals("copenhagen-mcp", claims.getSubject());
        assertNull(claims.getStringClaim("connection_id"));
    }
}
