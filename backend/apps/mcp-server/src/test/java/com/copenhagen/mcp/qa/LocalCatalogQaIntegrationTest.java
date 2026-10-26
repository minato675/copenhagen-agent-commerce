package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.FakeMcpTokenVerifier;
import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.McpTestSupport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCatalogQaIntegrationTest {

    private static ConfigurableApplicationContext application;
    private static McpSyncClient client;
    private static HttpClient http;
    private static Path catalog;
    private static String baseUrl;

    @BeforeAll
    static void setUp() throws Exception {
        catalog = Files.createTempFile("qa-catalog-test-", ".json");
        Files.write(catalog, McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("format", 1, "mode", "QA",
                "products", List.of(QaCoreServerTest.product("fixture-1", "group", 1000)))));
        System.setProperty("copenhagen.qa.catalog", catalog.toString());
        try {
            application = new SpringApplicationBuilder(McpServerApplication.class, LocalCatalogQa.Configuration.class)
                    .run("--server.address=127.0.0.1", "--server.port=0", "--mcp.ui.enabled=true",
                            "--mcp.catalog.enabled=true", "--mcp.catalog.required-scopes=qa:catalog:read",
                            "--mcp.catalog.detail-enabled=true",
                            "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                            "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                            "--mcp.auth.required-scopes=mcp:access", "--spring.main.banner-mode=off");
        } finally {
            System.clearProperty("copenhagen.qa.catalog");
        }
        baseUrl = McpTestSupport.baseUrl(application);
        http = HttpClient.newHttpClient();
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            client.closeGracefully();
        }
        if (application != null) {
            application.close();
        }
        if (http != null) {
            http.close();
        }
        if (catalog != null) {
            Files.deleteIfExists(catalog);
        }
    }

    @Test
    void catalog_roundTripsThroughMcpAndCoreWithoutIdentityLeak() {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                .name("search_products").arguments(Map.of("category", "group")).build());
        assertFalse(result.isError());
        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertEquals("QA", data.get("mode"));
        assertEquals("fixture-1", ((Map<?, ?>) ((List<?>) data.get("products")).getFirst()).get("productId"));
        assertFalse(data.containsKey("publicUserRef"));
        assertTrue(client.callTool(McpSchema.CallToolRequest.builder().name("search_products")
                .arguments(Map.of("userId", "another-user")).build()).isError());
    }

    @Test
    void onlyDisplayToolsMountTheCatalogAndResourceIsVersioned() {
        List<McpSchema.Tool> tools = client.listTools().tools();
        assertEquals(5, tools.size());
        McpSchema.Tool search = tools.stream().filter(tool -> tool.name().equals("search_products"))
                .findFirst().orElseThrow();
        assertFalse(search.meta().containsKey("openai/outputTemplate"));
        McpSchema.Tool display = tools.stream().filter(tool -> tool.name().equals("display_products"))
                .findFirst().orElseThrow();
        String uri = (String) display.meta().get("openai/outputTemplate");
        assertTrue(uri.matches("ui://copenhagen/catalog-[a-f0-9]{16}\\.html"));
        McpSchema.TextResourceContents html = (McpSchema.TextResourceContents) client
                .readResource(new McpSchema.ReadResourceRequest(uri)).contents().getFirst();
        assertTrue(html.text().contains("search-form"));
        assertTrue(html.text().contains("comparison"));
    }

    @Test
    void previewRejectsForeignOriginsAndNonCatalogTools() throws Exception {
        HttpResponse<String> preview = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/qa")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, preview.statusCode());
        assertEquals("catalog-mock", preview.headers().firstValue("X-Copenhagen-Qa").orElseThrow());
        HttpRequest rejected = HttpRequest.newBuilder(URI.create(baseUrl + "/qa/tools"))
                .header("Origin", "https://untrusted.example").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"search_products\",\"arguments\":{}}"))
                .build();
        assertEquals(403, http.send(rejected, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest unknown = HttpRequest.newBuilder(URI.create(baseUrl + "/qa/tools"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"pay\",\"arguments\":{}}"))
                .build();
        assertEquals(400, http.send(unknown, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void detail_roundTripPreservesUnknownsAndDoesNotMountAnotherUi() {
        McpSchema.Tool tool = client.listTools().tools().stream()
                .filter(item -> item.name().equals("get_product_detail")).findFirst().orElseThrow();
        assertFalse(tool.meta().containsKey("openai/outputTemplate"));
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                .name("get_product_detail").arguments(Map.of("productId", "fixture-1", "merchantId", "qa-a")).build());
        assertFalse(result.isError());
        Map<?, ?> data = (Map<?, ?>) result.structuredContent();
        assertEquals("FOUND", data.get("status"));
        assertEquals("UNKNOWN", ((Map<?, ?>) ((Map<?, ?>) data.get("product")).get("shipping")).get("status"));
        McpSchema.CallToolResult missing = client.callTool(McpSchema.CallToolRequest.builder()
                .name("get_product_detail").arguments(Map.of("productId", "absent", "merchantId", "qa-a")).build());
        assertEquals("NOT_FOUND", ((Map<?, ?>) missing.structuredContent()).get("status"));
    }
}
