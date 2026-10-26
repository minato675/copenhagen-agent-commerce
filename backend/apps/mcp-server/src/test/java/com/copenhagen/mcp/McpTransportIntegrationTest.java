package com.copenhagen.mcp;

import com.copenhagen.mcp.core.CoreClient;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTransportIntegrationTest {

    private static final AtomicInteger HANDLER_CALLS = new AtomicInteger();
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static FakeCore core;
    private static ConfigurableApplicationContext application;
    private static McpSyncClient client;
    private static HttpClient http;
    private static String baseUrl;

    @BeforeAll
    static void setUp() throws Exception {
        core = new FakeCore();
        application = new SpringApplicationBuilder(McpServerApplication.class, FixtureConfiguration.class,
                McpAuthTestConfiguration.class)
                .run("--server.port=0", "--services.core.base-url=" + core.baseUrl(),
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access",
                        "--services.core.request-timeout=1s", "--spring.main.banner-mode=off");
        baseUrl = McpTestSupport.baseUrl(application);
        http = HttpClient.newHttpClient();
        client = McpTestSupport.client(baseUrl);
        client.initialize();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.closeGracefully();
        }
        if (application != null) {
            application.close();
        }
        if (core != null) {
            core.close();
        }
        if (http != null) {
            http.close();
        }
    }

    @Test
    void initialize_andListTools_exposesDeclaredSchemasThroughHttp() {
        assertEquals("copenhagen-mcp", client.getServerInfo().name());
        assertTrue(client.isInitialized());
        List<McpSchema.Tool> tools = client.listTools().tools();
        assertEquals(4, tools.size());
        McpSchema.Tool echo = tools.stream().filter(tool -> tool.name().equals("fixture_echo"))
                .findFirst().orElseThrow();
        assertEquals(McpTestSupport.VALUE_SCHEMA, echo.inputSchema());
        assertEquals(McpTestSupport.VALUE_SCHEMA, echo.outputSchema());
    }

    @Test
    void callTool_validArguments_returnsMatchingTextAndStructuredContent() throws Exception {
        int before = HANDLER_CALLS.get();
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                .name("fixture_echo").arguments(Map.of("value", "hello")).build());
        assertFalse(result.isError());
        assertEquals(Map.of("value", "hello"), result.structuredContent());
        assertConsistentContent(result);
        assertEquals(before + 1, HANDLER_CALLS.get());
    }

    @Test
    void callTool_invalidArguments_stopsHandlerAndReturnsSafeInputError() throws Exception {
        List<Map<String, Object>> invalid = List.of(Map.of(), Map.of("value", 7),
                Map.of("value", "x".repeat(65)), Map.of("value", "ok", "private", FakeCore.PRIVATE_MARKER));
        int before = HANDLER_CALLS.get();
        for (Map<String, Object> arguments : invalid) {
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("fixture_echo").arguments(arguments).build());
            assertError(result, "INVALID_ARGUMENT");
        }
        assertEquals(before, HANDLER_CALLS.get());
    }

    @Test
    void callTool_unknownTool_returnsProtocolError() {
        assertThrows(McpError.class, () -> client.callTool(McpSchema.CallToolRequest.builder()
                .name("fixture_missing").arguments(Map.of()).build()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixture_bad_output", "fixture_exception"})
    void callTool_internalFailure_doesNotExposePrivateData(String name) throws Exception {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                .name(name).arguments(Map.of()).build());
        assertError(result, "INTERNAL_ERROR");
    }

    @Test
    void callTool_fakeCore_validatesRealHttpResult() throws Exception {
        McpSchema.CallToolResult result = callCore("success");
        assertFalse(result.isError());
        assertEquals(Map.of("value", "confirmed"), result.structuredContent());
        assertConsistentContent(result);
        assertNull(core.lastAuthorization());
        assertFalse(core.lastBody().contains(FakeMcpTokenVerifier.DEFAULT_TOKEN));
    }

    @Test
    void callTool_fakeCoreFailures_preservesErrorCategories() throws Exception {
        assertError(callCore("service-error"), "CORE_SERVICE_ERROR");
        assertError(callCore("wrong-schema"), "CORE_INVALID_RESPONSE");
        assertError(callCore("timeout"), "CORE_TIMEOUT");
    }

    @Test
    void post_rawInitializeAndNotification_acceptsProtocolHandshake() throws Exception {
        HttpResponse<String> initialized = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                "protocolVersion":"2025-11-25","capabilities":{},
                "clientInfo":{"name":"raw-test","version":"1"}}}
                """, null, null);
        assertEquals(200, initialized.statusCode());
        String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertNotNull(session);
        HttpResponse<String> notification = post(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session, null);
        assertEquals(202, notification.statusCode());
        HttpResponse<String> unknown = post(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"unsupported/method\"}", session, null);
        assertTrue(unknown.body().contains("-32601"));
        HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Authorization", "Bearer " + FakeMcpTokenVerifier.DEFAULT_TOKEN)
                .header("Mcp-Session-Id", session).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deleted.statusCode());
    }

    @Test
    void post_malformedJson_returnsGenericProtocolError() throws Exception {
        HttpResponse<String> result = post("{\"" + FakeCore.PRIVATE_MARKER + "\":", null, null);
        assertEquals(400, result.statusCode());
        assertTrue(result.body().contains("-32700"));
        assertFalse(result.body().contains(FakeCore.PRIVATE_MARKER));
    }

    @Test
    void post_wrongArgumentShape_returnsGenericProtocolError() throws Exception {
        HttpResponse<String> result = post("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                "params":{"name":"fixture_echo","arguments":["PRIVATE_FIXTURE_MARKER"]}}
                """, null, null);
        assertEquals(400, result.statusCode());
        assertFalse(result.body().contains(FakeCore.PRIVATE_MARKER));
    }

    @Test
    void post_oversizedBody_rejectsBeforeParsing() throws Exception {
        assertEquals(413, post("x".repeat(262145), null, null).statusCode());
    }

    @Test
    void post_untrustedOrigin_rejectsRequest() throws Exception {
        HttpResponse<String> result = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
                null, "https://untrusted.example");
        assertEquals(403, result.statusCode());
    }

    private McpSchema.CallToolResult callCore(String scenario) {
        return client.callTool(McpSchema.CallToolRequest.builder().name("fixture_core")
                .arguments(Map.of("scenario", scenario)).build());
    }

    private void assertError(McpSchema.CallToolResult result, String code) throws Exception {
        assertTrue(result.isError());
        assertEquals(code, ((Map<?, ?>) ((Map<?, ?>) result.structuredContent()).get("error")).get("code"));
        assertFalse(MAPPER.writeValueAsString(result).contains(FakeCore.PRIVATE_MARKER));
        assertConsistentContent(result);
    }

    private void assertConsistentContent(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent text = (McpSchema.TextContent) result.content().getFirst();
        assertEquals(result.structuredContent(), MAPPER.readValue(text.text(), Object.class));
    }

    private HttpResponse<String> post(String body, String session, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Authorization", "Bearer " + FakeMcpTokenVerifier.DEFAULT_TOKEN)
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {

        @Bean
        McpToolDefinition fixtureEcho() {
            return new McpToolDefinition(McpSchema.Tool.builder("fixture_echo", McpTestSupport.VALUE_SCHEMA)
                    .outputSchema(McpTestSupport.VALUE_SCHEMA).build(), (exchange, request) -> {
                        HANDLER_CALLS.incrementAndGet();
                        return Map.of("value", request.arguments().get("value"));
                    });
        }

        @Bean
        McpToolDefinition fixtureCore(CoreClient coreClient) {
            Map<String, Object> schema = Map.of("type", "object", "properties",
                    Map.of("scenario", Map.of("type", "string", "enum",
                            List.of("success", "service-error", "wrong-schema", "timeout"))),
                    "required", List.of("scenario"), "additionalProperties", false);
            return new McpToolDefinition(McpSchema.Tool.builder("fixture_core", schema)
                    .outputSchema(McpTestSupport.VALUE_SCHEMA).build(), (exchange, request) ->
                    coreClient.post("/fixture/" + request.arguments().get("scenario"), Map.of(),
                            McpTestSupport.VALUE_SCHEMA));
        }

        @Bean
        McpToolDefinition fixtureBadOutput() {
            return new McpToolDefinition(McpSchema.Tool.builder("fixture_bad_output", Map.of("type", "object"))
                    .outputSchema(McpTestSupport.VALUE_SCHEMA).build(),
                    (exchange, request) -> Map.of("private", FakeCore.PRIVATE_MARKER));
        }

        @Bean
        McpToolDefinition fixtureException() {
            return new McpToolDefinition(McpSchema.Tool.builder("fixture_exception", Map.of("type", "object"))
                    .outputSchema(McpTestSupport.VALUE_SCHEMA).build(), (exchange, request) -> {
                        throw new IllegalStateException(FakeCore.PRIVATE_MARKER);
                    });
        }
    }
}
