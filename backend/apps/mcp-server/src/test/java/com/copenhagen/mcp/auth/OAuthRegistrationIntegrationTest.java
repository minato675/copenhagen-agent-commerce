package com.copenhagen.mcp.auth;

import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.McpTestSupport;
import com.copenhagen.mcp.core.ServiceTokenProvider;
import com.copenhagen.mcp.integration.CoreCommerceService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthRegistrationIntegrationTest {
    private static final String ISSUER = "https://core.example";
    private static final String RESOURCE = "https://mcp.example/mcp";
    private static HttpServer jwks;
    private static ECKey key;
    private static ConfigurableApplicationContext application;

    @BeforeAll
    static void start() throws Exception {
        key = new ECKeyGenerator(Curve.P_256).keyID("registration-test").generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwks.start();
        application = new SpringApplicationBuilder(McpServerApplication.class)
                .profiles("oauth-registration")
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--mcp.auth.issuer=" + ISSUER, "--mcp.auth.resource=" + RESOURCE,
                        "--mcp.auth.jwt.jwks-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks",
                        "--mcp.server.allowed-hosts=127.0.0.1:*",
                        "--services.core.auth.token-uri=",
                        "--services.core.auth.private-jwk-file=");
    }

    @AfterAll
    static void stop() {
        if (application != null) {
            application.close();
        }
        if (jwks != null) {
            jwks.stop(0);
        }
    }

    @Test
    void profile_startsWithoutAppLinkAndServiceKeyAndExposesOnlyConnectionTools() throws Exception {
        assertTrue(application.getBeansOfType(CoreCommerceService.class).isEmpty());
        assertTrue(application.getBeansOfType(ServiceTokenProvider.class).isEmpty());
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token(key, ISSUER, RESOURCE, "catalog:read", 240))) {
            client.initialize();
            var tools = client.listTools().tools();
            assertEquals(Set.of("open_copenhagen", "check_connection"),
                    tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet()));
            for (var tool : tools) {
                assertEquals(List.of(Map.of("type", "oauth2", "scopes", List.of("catalog:read"))),
                        tool.meta().get("securitySchemes"));
                var result = client.callTool(McpSchema.CallToolRequest.builder().name(tool.name())
                        .arguments(Map.of()).build());
                assertFalse(result.isError());
                assertEquals("connected", ((Map<?, ?>) result.structuredContent()).get("status"));
                assertFalse(result.structuredContent().toString().contains("test-user"));
            }
            var resource = client.listResources().resources().getFirst();
            var html = (McpSchema.TextResourceContents) client.readResource(
                    new McpSchema.ReadResourceRequest(resource.uri())).contents().getFirst();
            assertTrue(html.text().contains("COPENHAGEN"));
        }
    }

    @Test
    void discovery_advertisesOAuthAndUnauthenticatedRequestsAreChallenged() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var metadata = http.send(HttpRequest.newBuilder(URI.create(McpTestSupport.baseUrl(application)
                    + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metadata.statusCode());
            assertTrue(metadata.body().contains(ISSUER));
            assertTrue(metadata.body().contains(RESOURCE));
            assertTrue(metadata.body().contains("catalog:read"));
            assertFalse(metadata.body().contains("cart:write"));
            var denied = request(null);
            assertEquals(401, denied.statusCode());
            assertTrue(denied.headers().firstValue("WWW-Authenticate").orElseThrow()
                    .contains("https://mcp.example/.well-known/oauth-protected-resource/mcp"));
        }
    }

    @Test
    void authentication_rejectsWrongSignatureIssuerAudienceExpiryAndScope() throws Exception {
        ECKey other = new ECKeyGenerator(Curve.P_256).keyID(key.getKeyID()).generate();
        assertEquals(401, request(token(other, ISSUER, RESOURCE, "catalog:read", 240)).statusCode());
        assertEquals(401, request(token(key, "https://other.example", RESOURCE, "catalog:read", 240)).statusCode());
        assertEquals(401, request(token(key, ISSUER, "https://other.example/mcp", "catalog:read", 240)).statusCode());
        assertEquals(401, request(token(key, ISSUER, RESOURCE, "catalog:read", -1)).statusCode());
        assertEquals(403, request(token(key, ISSUER, RESOURCE, "cart:read", 240)).statusCode());
    }

    private static HttpResponse<String> request(String token) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create(McpTestSupport.baseUrl(application) + "/mcp"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"));
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String token(ECKey signer, String issuer, String audience, String scope, int expiresIn)
            throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signer.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("test-user")
                        .issueTime(Date.from(now.minusSeconds(30)))
                        .expirationTime(Date.from(now.plusSeconds(expiresIn)))
                        .claim("connection_id", "test-connection").claim("scope", scope).build());
        jwt.sign(new ECDSASigner(signer));
        return jwt.serialize();
    }
}
