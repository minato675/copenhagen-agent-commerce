package com.copenhagen.mcp.merchant;

import com.copenhagen.mcp.FakeMcpTokenVerifier;
import com.copenhagen.mcp.McpAuthTestConfiguration;
import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.McpTestSupport;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.REGISTRATIONS;
import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.change;
import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.response;
import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.shop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantToolIntegrationTest {

    private static final String TOOL = "list_shopping_malls";
    private static final String READ_SCOPE = "fixture:shops:read";
    private static final String PRIVATE_MARKER = "PRIVATE_MERCHANT_MARKER";
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static FakeMerchantCore core;
    private static ConfigurableApplicationContext application;
    private static FakeMcpTokenVerifier verifier;
    private static String baseUrl;

    @BeforeAll
    static void setUp() throws Exception {
        core = new FakeMerchantCore();
        application = new SpringApplicationBuilder(McpServerApplication.class, FixtureConfiguration.class,
                McpAuthTestConfiguration.class)
                .run("--server.port=0", "--services.core.base-url=" + core.baseUrl(),
                        "--services.core.request-timeout=1s", "--mcp.merchants.enabled=true",
                        "--mcp.merchants.required-scopes=" + READ_SCOPE,
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access", "--spring.main.banner-mode=off");
        baseUrl = McpTestSupport.baseUrl(application);
        verifier = application.getBean(FakeMcpTokenVerifier.class);
    }

    @AfterAll
    static void tearDown() {
        if (application != null) {
            application.close();
        }
        if (core != null) {
            core.close();
        }
    }

    @Test
    void listTools_enabledAdapter_exposesReadOnlyToolAndSchema() throws Exception {
        try (McpSyncClient client = client("discovery")) {
            List<McpSchema.Tool> tools = client.listTools().tools();
            assertEquals(1, tools.size());
            McpSchema.Tool tool = tools.getFirst();
            assertEquals(TOOL, tool.name());
            assertEquals(MerchantSchemas.INPUT, tool.inputSchema());
            assertEquals(MerchantSchemas.OUTPUT, tool.outputSchema());
            assertTrue(tool.annotations().readOnlyHint());
            assertFalse(tool.annotations().destructiveHint());
            assertTrue(tool.annotations().idempotentHint());
            assertTrue(tool.annotations().openWorldHint());
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> metadata = http.send(HttpRequest.newBuilder(
                    URI.create(baseUrl + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metadata.statusCode());
            assertTrue(metadata.body().contains(READ_SCOPE));
        }
    }

    @Test
    void callTool_mixedShops_preservesSuccessConnectionAndPartialFailure() throws Exception {
        String user = "mixed";
        core.respond(user, response(user, user + "-connection", List.of(shop("shop-a", "AVAILABLE", "CONNECTED"),
                shop("shop-b", "CONNECTION_REQUIRED", "NOT_CONNECTED"), shop("shop-c", "UNAVAILABLE", "UNKNOWN"))));
        try (McpSyncClient client = client(user)) {
            McpSchema.CallToolResult result = call(client, Map.of());
            assertFalse(result.isError());
            assertConsistent(result);
            List<?> shops = shops(result);
            assertEquals(3, shops.size());
            assertEquals("AVAILABLE", ((Map<?, ?>) shops.get(0)).get("status"));
            assertEquals("CONNECT_SHOP", ((Map<?, ?>) shops.get(1)).get("nextAction"));
            assertEquals("RETRY_LATER", ((Map<?, ?>) shops.get(2)).get("nextAction"));
            for (Object shop : shops) {
                assertEquals(List.of(), ((Map<?, ?>) shop).get("availableMcpTools"));
            }
            assertTrue(core.received(user, user + "-connection"));
            assertEquals(0, core.unexpectedRequestCount());
            String json = MAPPER.writeValueAsString(result);
            assertFalse(json.contains("publicUserRef"));
            assertFalse(json.contains(user + "-connection"));
        }
    }

    @Test
    void callTool_emptyDirectory_returnsSuccessfulEmptyList() throws Exception {
        core.respond("empty", response("empty", "empty-connection", List.of()));
        try (McpSyncClient client = client("empty")) {
            McpSchema.CallToolResult result = call(client, Map.of());
            assertFalse(result.isError());
            assertEquals(List.of(), shops(result));
        }
    }

    @Test
    void callTool_missingReadScope_rejectsBeforeCoreWithAuthChallenge() throws Exception {
        int before = core.requestCount();
        try (McpSyncClient client = McpTestSupport.client(baseUrl)) {
            client.initialize();
            McpSchema.CallToolResult result = call(client, Map.of());
            assertError(result, "INSUFFICIENT_SCOPE");
            String metadata = MAPPER.writeValueAsString(result.meta());
            assertTrue(metadata.contains("mcp/www_authenticate"));
            assertTrue(metadata.contains(READ_SCOPE));
        }
        assertEquals(before, core.requestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "userId", "publicUserRef", "merchantId", "url", "endpoint", "cursor"})
    void callTool_identityOrDestinationArgument_rejectsBeforeCore(String field) throws Exception {
        int before = core.requestCount();
        try (McpSyncClient client = client("invalid-" + field)) {
            assertError(call(client, Map.of(field, PRIVATE_MARKER)), "INVALID_ARGUMENT");
        }
        assertEquals(before, core.requestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"publicUserRef", "connectionRef"})
    void callTool_coreReturnsAnotherIdentity_rejectsWithoutLeakingShopName(String field) throws Exception {
        String user = "wrong-" + field;
        core.respond(user, change(response(user, user + "-connection", List.of(change(
                shop("shop-a", "AVAILABLE", "CONNECTED"), "displayName", PRIVATE_MARKER))), field, "other"));
        try (McpSyncClient client = client(user)) {
            assertError(call(client, Map.of()), "ACCESS_DENIED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-json", "bad-schema", "redirect", "failure", "timeout"})
    void callTool_coreFailure_preservesSafeErrorAndNeverFollowsRedirect(String scenario) throws Exception {
        int before = core.requestCount();
        String expected;
        switch (scenario) {
            case "bad-json" -> {
                core.respond(scenario, 200, PRIVATE_MARKER);
                expected = "CORE_INVALID_RESPONSE";
            }
            case "bad-schema" -> {
                core.respond(scenario, 200, "{\"private\":\"" + PRIVATE_MARKER + "\"}");
                expected = "CORE_INVALID_RESPONSE";
            }
            case "timeout" -> {
                core.respond(scenario, 0, "");
                expected = "CORE_TIMEOUT";
            }
            default -> {
                core.respond(scenario, scenario.equals("redirect") ? 302 : 503, PRIVATE_MARKER);
                expected = "CORE_SERVICE_ERROR";
            }
        }
        try (McpSyncClient client = client(scenario)) {
            assertError(call(client, Map.of()), expected);
        }
        assertEquals(before + 1, core.requestCount());
        assertEquals(0, core.unexpectedRequestCount());
    }

    @Test
    void callTool_concurrentUsers_preservesTheirConnectionStates() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                String user = "concurrent-" + index;
                String status = index % 2 == 0 ? "AVAILABLE" : "CONNECTION_REQUIRED";
                String connection = index % 2 == 0 ? "CONNECTED" : "EXPIRED";
                core.respond(user, response(user, user + "-connection", List.of(change(
                        shop("shop-a", status, connection), "displayName", user))));
                results.add(workers.submit(() -> {
                    start.await();
                    try (McpSyncClient client = client(user)) {
                        McpSchema.CallToolResult result = call(client, Map.of());
                        assertFalse(result.isError());
                        Map<?, ?> shop = (Map<?, ?>) shops(result).getFirst();
                        assertEquals(user, shop.get("displayName"));
                        assertEquals(status, shop.get("status"));
                        assertTrue(core.received(user, user + "-connection"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get();
            }
        }
    }

    private McpSyncClient client(String user) {
        String token = verifier.issue(FakeMcpTokenVerifier.connected(user, user + "-connection",
                Set.of("mcp:access", READ_SCOPE)));
        McpSyncClient client = McpTestSupport.client(baseUrl, token);
        client.initialize();
        return client;
    }

    private McpSchema.CallToolResult call(McpSyncClient client, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder().name(TOOL).arguments(arguments).build());
    }

    private List<?> shops(McpSchema.CallToolResult result) {
        return (List<?>) ((Map<?, ?>) result.structuredContent()).get("shops");
    }

    private void assertError(McpSchema.CallToolResult result, String code) throws Exception {
        assertTrue(result.isError());
        assertEquals(code, ((Map<?, ?>) ((Map<?, ?>) result.structuredContent()).get("error")).get("code"));
        assertFalse(MAPPER.writeValueAsString(result).contains(PRIVATE_MARKER));
        assertConsistent(result);
    }

    private void assertConsistent(McpSchema.CallToolResult result) throws Exception {
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertEquals(result.structuredContent(), MAPPER.readValue(text, Object.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {

        @Bean
        CoreMerchantDirectory fixtureMerchantDirectory(CoreClient client) {
            return new CoreMerchantDirectory() {
                @Override
                public Map<String, Set<Capability>> registeredCapabilities() {
                    return REGISTRATIONS;
                }

                @Override
                public Map<String, Object> list(McpIdentity identity) {
                    // 합의 전 wire 형식은 test에만 둔다. 실제 서비스 인증을 구현한 것으로 간주하지 않는다.
                    return client.post(FakeMerchantCore.PATH, Map.of("publicUserRef", identity.publicUserRef(),
                            "connectionRef", identity.connectionRef()), MerchantSchemas.CORE_RESPONSE);
                }
            };
        }
    }
}
