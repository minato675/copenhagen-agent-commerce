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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCartQaIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void cartSurvivesNewSearchAndNewMcpSessionAndOnlyQa3ExposesWrites() throws Exception {
        Path catalog = directory.resolve("catalog.json");
        Files.write(catalog, McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("format", 1, "mode", "QA",
                "products", List.of(QaCoreServerTest.product("fixture-1", "group", 1000)))));
        System.setProperty("copenhagen.qa.catalog", catalog.toString());
        System.setProperty("copenhagen.qa.cart", directory.resolve("cart.json").toString());
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(McpServerApplication.class,
                LocalCartQa.Configuration.class).run("--server.address=127.0.0.1", "--server.port=0",
                        "--mcp.ui.enabled=true", "--mcp.catalog.enabled=true", "--mcp.catalog.detail-enabled=true",
                        "--mcp.catalog.required-scopes=qa:catalog:read", "--mcp.cart.enabled=true",
                        "--mcp.cart.read-scopes=qa:cart:read", "--mcp.cart.write-scopes=qa:cart:write",
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access", "--spring.main.banner-mode=off");
                HttpClient http = HttpClient.newHttpClient()) {
            String base = McpTestSupport.baseUrl(application);
            Map<String, Object> input;
            try (McpSyncClient client = client(base)) {
                Map<String, Object> cart = map(call(client, "get_cart", Map.of()).get("cart"));
                input = Map.of("cartId", cart.get("cartId"), "expectedVersion", cart.get("version"),
                        "productId", "fixture-1", "merchantId", "qa-a", "optionId", "", "quantity", 2,
                        "idempotencyKey", "request-key-integration");
                assertEquals("APPLIED", call(client, "add_cart_item", input).get("status"));
                call(client, "search_products", Map.of("query", "absent"));
                assertEquals(1, ((List<?>) map(call(client, "get_cart", Map.of()).get("cart")).get("lines")).size());
                McpSchema.Tool add = client.listTools().tools().stream()
                        .filter(tool -> tool.name().equals("add_cart_item")).findFirst().orElseThrow();
                assertFalse(add.annotations().readOnlyHint());
                assertFalse(client.listTools().tools().stream()
                        .anyMatch(tool -> tool.name().equals("prepare_purchase_handoff")));
                McpSchema.Tool display = client.listTools().tools().stream()
                        .filter(tool -> tool.name().equals("open_copenhagen")).findFirst().orElseThrow();
                String uri = (String) display.meta().get("openai/outputTemplate");
                String html = ((McpSchema.TextResourceContents) client
                        .readResource(new McpSchema.ReadResourceRequest(uri)).contents().getFirst()).text();
                assertTrue(html.contains("data-cart-enabled=\"true\""));
            }
            try (McpSyncClient client = client(base)) {
                assertEquals("APPLIED", call(client, "add_cart_item", input).get("status"));
                Map<String, Object> view = call(client, "get_cart", Map.of());
                assertEquals("ESTIMATED", view.get("status"));
                assertEquals(2, QaCatalogStore.maps(map(view.get("cart")).get("lines")).getFirst().get("quantity"));
                assertFalse(view.containsKey("publicUserRef"));
            }
            HttpResponse<String> preview = http.send(HttpRequest.newBuilder(URI.create(base + "/qa")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("cart-mock", preview.headers().firstValue("X-Copenhagen-Qa").orElseThrow());
            assertTrue(preview.body().contains("window.openai ="));
            assertTrue(preview.body().contains("data-cart-enabled=\"true\""));
        } finally {
            System.clearProperty("copenhagen.qa.catalog");
            System.clearProperty("copenhagen.qa.cart");
        }
    }

    static McpSyncClient client(String base) {
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(base).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
        return client;
    }

    static Map<String, Object> call(McpSyncClient client, String name, Map<String, Object> request) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                .name(name).arguments(request).build());
        assertFalse(result.isError());
        return map(result.structuredContent());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
