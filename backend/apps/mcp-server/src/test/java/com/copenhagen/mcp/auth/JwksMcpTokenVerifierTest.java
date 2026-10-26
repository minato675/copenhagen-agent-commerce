package com.copenhagen.mcp.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwksMcpTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private RSAKey signingKey;
    private HttpServer server;
    private McpAuthenticationService authentication;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("core-test").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var verifier = new JwksMcpTokenVerifier(new AgentJwtProperties(true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks")),
                HttpClient.newHttpClient(), clock);
        authentication = new McpAuthenticationService(new McpAuthProperties(URI.create("https://core.example"),
                URI.create("https://mcp.example/mcp"), Set.of("catalog:read")), () -> verifier, clock);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void authenticate_validJwt_retainsDelegationWithoutExposingToken() throws Exception {
        String token = token("https://core.example", "https://mcp.example/mcp", 300, "connection-1", signingKey);
        McpIdentity identity = authentication.authenticate(token);
        assertEquals("user-1", identity.publicUserRef());
        assertEquals(token, identity.agentAccessToken());
        assertFalse(identity.toString().contains(token));
        authentication.authenticate(token);
        assertEquals(1, requests.get());
    }

    @Test
    void authenticate_invalidClaimsOrSignature_rejects() throws Exception {
        for (String token : new String[] {
                token("wrong", "https://mcp.example/mcp", 300, "connection-1", signingKey),
                token("https://core.example", "wrong", 300, "connection-1", signingKey),
                token("https://core.example", "https://mcp.example/mcp", 301, "connection-1", signingKey),
                token("https://core.example", "https://mcp.example/mcp", 0, "connection-1", signingKey),
                token("https://core.example", "https://mcp.example/mcp", 300, "", signingKey),
                token("https://core.example", "https://mcp.example/mcp", 300, "connection-1",
                        new RSAKeyGenerator(2048).generate())}) {
            assertThrows(McpAuthException.class, () -> authentication.authenticate(token));
        }
    }

    @Test
    void configure_publicHttpJwks_rejects() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentJwtProperties(true, URI.create("http://core.example/jwks")));
    }

    private String token(String issuer, String audience, int ttl, String connection, RSAKey key) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("user-1")
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(ttl)))
                .claim("connection_id", connection).claim("scope", "catalog:read cart:read").build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("core-test").build(), claims);
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }
}
