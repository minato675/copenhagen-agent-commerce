package com.copenhagen.mcp;

import com.copenhagen.mcp.auth.VerifiedMcpToken;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAuthenticationIntegrationTest {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2025-11-25","capabilities":{},
            "clientInfo":{"name":"auth-test","version":"1"}}}
            """;
    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final AtomicInteger HANDLER_CALLS = new AtomicInteger();
    private static ConfigurableApplicationContext application;
    private static HttpClient http;
    private static FakeMcpTokenVerifier verifier;
    private static String baseUrl;

    @BeforeAll
    static void setUp() {
        application = new SpringApplicationBuilder(McpServerApplication.class, McpAuthTestConfiguration.class,
                Tools.class).run("--server.port=0", "--server.address=127.0.0.1",
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access", "--spring.main.banner-mode=off");
        verifier = application.getBean(FakeMcpTokenVerifier.class);
        baseUrl = McpTestSupport.baseUrl(application);
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() {
        if (application != null) {
            application.close();
        }
        if (http != null) {
            http.close();
        }
    }

    @Test
    void metadata_configuredAuth_exposesCanonicalResourceWithoutRequestHost() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/.well-known/oauth-protected-resource/mcp"))
                .header("X-Forwarded-Host", "attacker.example").GET());
        assertEquals(200, response.statusCode());
        Map<?, ?> body = MAPPER.readValue(response.body(), Map.class);
        assertEquals(FakeMcpTokenVerifier.RESOURCE, body.get("resource"));
        assertEquals(List.of(FakeMcpTokenVerifier.ISSUER), body.get("authorization_servers"));
        assertEquals(List.of("header"), body.get("bearer_methods_supported"));
        assertEquals(List.of("fixture:read", "mcp:access"), body.get("scopes_supported"));
        assertFalse(response.body().contains("attacker"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void request_missingToken_challengesEveryMethod(String method) throws Exception {
        HttpResponse<String> response = send(request(method, null, null, PING)
                .header("X-User-Id", "user-a").header("X-Public-User-Ref", "user-a"));
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("REAUTHENTICATE"));
        String challenge = response.headers().firstValue("WWW-Authenticate").orElseThrow();
        assertTrue(challenge.contains("resource_metadata="));
        assertTrue(challenge.contains("scope=\"mcp:access\""));
        assertFalse(challenge.contains("error="));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic something", "Bearer", "Bearer one two", "Bearer a,b"})
    void request_malformedAuthorization_rejectsWithoutEcho(String header) throws Exception {
        HttpResponse<String> response = send(request("POST", null, null, INITIALIZE)
                .header("Authorization", header));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("INVALID_AUTH_REQUEST"));
        assertFalse(response.body().contains(header));
    }

    @Test
    void request_duplicateHeadersAndQueryToken_rejectsAmbiguity() throws Exception {
        String token = token("user-a", "connection-a", Set.of("mcp:access"));
        assertEquals(400, send(request("POST", token, null, INITIALIZE)
                .header("Authorization", "Bearer " + token)).statusCode());
        HttpResponse<String> query = send(HttpRequest.newBuilder(URI.create(baseUrl + "/mcp?access_token=" + token))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(INITIALIZE)));
        assertEquals(400, query.statusCode());
        assertFalse(query.body().contains(token));
    }

    @Test
    void request_tamperedAndUnknownOpaqueToken_rejects() throws Exception {
        String token = token("user-a", "connection-a", Set.of("mcp:access"));
        for (String invalid : List.of(token + "x", UUID.randomUUID().toString())) {
            HttpResponse<String> response = send(request("POST", invalid, null, INITIALIZE));
            assertEquals(401, response.statusCode());
            assertFalse(response.body().contains(invalid));
            assertTrue(response.headers().firstValue("WWW-Authenticate").orElseThrow().contains("invalid_token"));
        }
    }

    @Test
    void request_missingGlobalScope_returns403Challenge() throws Exception {
        String token = token("user-a", "connection-a", Set.of("other:read"));
        HttpResponse<String> response = send(request("POST", token, null, INITIALIZE));
        assertEquals(403, response.statusCode());
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElseThrow().contains("insufficient_scope"));
        assertTrue(response.body().contains("REQUEST_PERMISSION"));
    }

    @Test
    void callTool_verifiedContext_ignoresSpoofedUserHeader() throws Exception {
        String token = token("user-a", "connection-a", Set.of("mcp:access", "fixture:read"));
        String session = initialize(token);
        String body = MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "fixture_identity", "arguments", Map.of())));
        HttpResponse<String> response = send(request("POST", token, session, body)
                .header("X-User-Id", "user-b").header("X-Public-User-Ref", "user-b"));
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("user-a"));
        assertFalse(response.body().contains("user-b"));
        assertFalse(response.body().contains(token));
        assertFalse(response.body().contains("connection-a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "userId", "publicUserRef"})
    void callTool_userIdentityInArguments_stopsHandler(String property) throws Exception {
        String token = token("user-a", "connection-a", Set.of("mcp:access", "fixture:read"));
        try (McpSyncClient client = McpTestSupport.client(baseUrl, token)) {
            client.initialize();
            int before = HANDLER_CALLS.get();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("fixture_identity").arguments(Map.of(property, "user-b")).build());
            assertTrue(result.isError());
            assertTrue(MAPPER.writeValueAsString(result).contains("INVALID_ARGUMENT"));
            assertEquals(before, HANDLER_CALLS.get());
        }
    }

    @Test
    void callTool_missingToolScope_stopsHandlerAndReturnsHostChallenge() throws Exception {
        String token = token("user-a", "connection-a", Set.of("mcp:access"));
        try (McpSyncClient client = McpTestSupport.client(baseUrl, token)) {
            client.initialize();
            int before = HANDLER_CALLS.get();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("fixture_identity").arguments(Map.of()).build());
            assertTrue(result.isError());
            assertTrue(result.meta().get("mcp/www_authenticate").toString().contains("fixture:read"));
            assertTrue(result.meta().get("mcp/www_authenticate").toString().contains("mcp:access"));
            assertEquals(before, HANDLER_CALLS.get());
        }
    }

    @Test
    void callTool_otherUsersResource_usesTrustedOwnerAndDenies() throws Exception {
        String token = token("user-a", "connection-a", Set.of("mcp:access"));
        try (McpSyncClient client = McpTestSupport.client(baseUrl, token)) {
            client.initialize();
            McpSchema.CallToolResult own = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("fixture_resource").arguments(Map.of("resourceRef", "resource-a")).build());
            assertFalse(own.isError());
            for (String resource : List.of("resource-b", "missing")) {
                McpSchema.CallToolResult denied = client.callTool(McpSchema.CallToolRequest.builder()
                        .name("fixture_resource").arguments(Map.of("resourceRef", resource)).build());
                assertTrue(denied.isError());
                assertTrue(MAPPER.writeValueAsString(denied).contains("ACCESS_DENIED"));
                assertFalse(MAPPER.writeValueAsString(denied).contains("user-b"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void request_otherUsersSession_deniesEveryMethod(String method) throws Exception {
        String owner = token("user-a", "connection-a", Set.of("mcp:access"));
        String other = token("user-b", "connection-b", Set.of("mcp:access"));
        String session = initialize(owner);
        assertEquals(403, send(request(method, other, session, PING)).statusCode());
        assertEquals(200, send(request("POST", owner, session, PING)).statusCode());
        assertEquals(200, send(request("DELETE", owner, session, "")).statusCode());
        assertEquals(404, send(request("POST", owner, session, PING)).statusCode());
    }

    @Test
    void request_sameUserNewConnection_cannotReuseOldSession() throws Exception {
        String owner = token("user-a", "connection-a", Set.of("mcp:access"));
        String session = initialize(owner);
        String renewed = token("user-a", "connection-a", Set.of("mcp:access"));
        assertEquals(200, send(request("POST", renewed, session, PING)).statusCode());
        String reconnected = token("user-a", "connection-new", Set.of("mcp:access"));
        assertEquals(403, send(request("POST", reconnected, session, PING)).statusCode());
    }

    @Test
    void request_revokedOrExpiredToken_cannotReuseEstablishedSession() throws Exception {
        String token = token("user-a", "connection-a", Set.of("mcp:access"));
        String session = initialize(token);
        verifier.revoke(token);
        assertEquals(401, send(request("POST", token, session, PING)).statusCode());
        verifier.replace(token, new VerifiedMcpToken(true, FakeMcpTokenVerifier.ISSUER,
                Set.of(FakeMcpTokenVerifier.RESOURCE), Instant.now().minusSeconds(1), null,
                "user-a", "connection-a", true, Set.of("mcp:access")));
        assertEquals(401, send(request("POST", token, session, PING)).statusCode());
    }

    @Test
    void request_disconnectedAccount_cannotReuseEstablishedSession() throws Exception {
        String token = token("user-a", "connection-a", Set.of("mcp:access"));
        String session = initialize(token);
        verifier.replace(token, new VerifiedMcpToken(true, FakeMcpTokenVerifier.ISSUER,
                Set.of(FakeMcpTokenVerifier.RESOURCE), Instant.now().plusSeconds(600), null,
                "user-a", "connection-a", false, Set.of("mcp:access")));
        HttpResponse<String> response = send(request("POST", token, session, PING));
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("RECONNECT_ACCOUNT"));
    }

    @Test
    void callTool_concurrentUsers_keepsRequestIdentitiesSeparate() {
        List<CompletableFuture<String>> calls = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                    String user = "parallel-user-" + index;
                    String token = token(user, "parallel-connection-" + index, Set.of("mcp:access", "fixture:read"));
                    try (McpSyncClient client = McpTestSupport.client(baseUrl, token)) {
                        client.initialize();
                        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                                .name("fixture_identity").arguments(Map.of()).build());
                        assertFalse(result.isError());
                        assertEquals(user, ((Map<?, ?>) result.structuredContent()).get("value"));
                    }
                    return user;
                })).toList();
        calls.forEach(CompletableFuture::join);
    }

    private static String token(String user, String connection, Set<String> scopes) {
        return verifier.issue(FakeMcpTokenVerifier.connected(user, connection, scopes));
    }

    private String initialize(String token) throws Exception {
        HttpResponse<String> response = send(request("POST", token, null, INITIALIZE));
        assertEquals(200, response.statusCode(), response.body());
        String session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertEquals(202, send(request("POST", token, session,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).statusCode());
        return session;
    }

    private HttpRequest.Builder request(String method, String token, String session, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (session != null) {
            request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
        }
        return request.method(method, HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Tools {

        @Bean
        McpToolDefinition identityTool() {
            Map<String, Object> input = Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
            return new McpToolDefinition(McpSchema.Tool.builder("fixture_identity", input)
                    .outputSchema(McpTestSupport.VALUE_SCHEMA).build(), Set.of("fixture:read"), (identity, request) -> {
                        HANDLER_CALLS.incrementAndGet();
                        return Map.of("value", identity.publicUserRef());
                    });
        }

        @Bean
        McpToolDefinition ownedResourceTool() {
            Map<String, String> ownersFromFakeCore = Map.of("resource-a", "user-a", "resource-b", "user-b");
            Map<String, Object> input = Map.of("type", "object", "properties",
                    Map.of("resourceRef", Map.of("type", "string")), "required", List.of("resourceRef"),
                    "additionalProperties", false);
            return new McpToolDefinition(McpSchema.Tool.builder("fixture_resource", input)
                    .outputSchema(McpTestSupport.VALUE_SCHEMA).build(), (identity, request) -> {
                        identity.requireOwner(ownersFromFakeCore.get(request.arguments().get("resourceRef")));
                        return Map.of("value", "owned-resource");
                    });
        }
    }
}
