package com.copenhagen.core.agent.token;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.copenhagen.core.agent.client.OauthClient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAssertionValidatorTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CLIENT_ID = "copenhagen-mcp";
    private static final String TOKEN_ENDPOINT = "https://core.example.com/oauth/token";
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private FakeJtiRepository jtiRepository;
    private ClientAssertionValidator validator;
    private ECKey keyPair;
    private OauthClient client;

    @BeforeEach
    void setUp() throws Exception {
        jtiRepository = new FakeJtiRepository();
        validator = new ClientAssertionValidator(jtiRepository, Clock.fixed(BASE_INSTANT, ZoneOffset.UTC));
        keyPair = new ECKeyGenerator(Curve.P_256).keyID("mcp-key-1").generate();
        String clientJwks = new JWKSet(keyPair.toPublicJWK()).toString();
        client = new OauthClient(
                1L, CLIENT_ID, null, "Copenhagen MCP Server", OauthClient.TYPE_CONFIDENTIAL,
                "[]", "[\"catalog:read\"]", null, OauthClient.STATUS_ACTIVE, clientJwks);
    }

    @Test
    @DisplayName("올바른 assertion은 예외 없이 통과하고 jti를 소비한다")
    void validate_validAssertion_succeeds() throws Exception {
        String assertion = signAssertion(CLIENT_ID, TOKEN_ENDPOINT, UUID.randomUUID().toString(), DEFAULT_TTL);

        validator.validate(client, assertion, TOKEN_ENDPOINT);

        assertTrue(jtiRepository.consumed());
    }

    @Test
    @DisplayName("같은 jti를 재사용하면 거부한다")
    void validate_reusedJti_rejected() throws Exception {
        String jti = UUID.randomUUID().toString();
        String assertion = signAssertion(CLIENT_ID, TOKEN_ENDPOINT, jti, DEFAULT_TTL);
        validator.validate(client, assertion, TOKEN_ENDPOINT);
        String replay = signAssertion(CLIENT_ID, TOKEN_ENDPOINT, jti, DEFAULT_TTL);

        assertThrows(
                InvalidClientAssertionException.class,
                () -> validator.validate(client, replay, TOKEN_ENDPOINT));
    }

    @Test
    @DisplayName("audience가 token endpoint가 아니면 거부한다")
    void validate_wrongAudience_rejected() throws Exception {
        String assertion = signAssertion(
                CLIENT_ID, "https://wrong.example.com", UUID.randomUUID().toString(), DEFAULT_TTL);

        assertThrows(
                InvalidClientAssertionException.class,
                () -> validator.validate(client, assertion, TOKEN_ENDPOINT));
    }

    @Test
    @DisplayName("만료된 assertion은 거부한다")
    void validate_expiredAssertion_rejected() throws Exception {
        String assertion = signAssertion(
                CLIENT_ID, TOKEN_ENDPOINT, UUID.randomUUID().toString(), Duration.ofSeconds(-10));

        assertThrows(
                InvalidClientAssertionException.class,
                () -> validator.validate(client, assertion, TOKEN_ENDPOINT));
    }

    @Test
    @DisplayName("iss/sub이 client_id와 다르면 거부한다")
    void validate_wrongIssuer_rejected() throws Exception {
        String assertion = signAssertion(
                "someone-else", TOKEN_ENDPOINT, UUID.randomUUID().toString(), DEFAULT_TTL);

        assertThrows(
                InvalidClientAssertionException.class,
                () -> validator.validate(client, assertion, TOKEN_ENDPOINT));
    }

    private String signAssertion(String issuer, String audience, String jti, Duration ttl) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(issuer)
                .audience(audience)
                .jwtID(jti)
                .issueTime(Date.from(BASE_INSTANT))
                .expirationTime(Date.from(BASE_INSTANT.plus(ttl)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyPair.getKeyID()).build(), claims);
        jwt.sign(new ECDSASigner(keyPair));
        return jwt.serialize();
    }

    private static final class FakeJtiRepository implements OauthClientAssertionJtiRepository {
        private boolean consumed;

        @Override
        public boolean tryConsume(long oauthClientId, String jti, Instant expiresAt) {
            if (consumed) {
                return false;
            }
            consumed = true;
            return true;
        }

        boolean consumed() {
            return consumed;
        }
    }
}
