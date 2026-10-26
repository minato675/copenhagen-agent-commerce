package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.FakeMcpTokenVerifier;
import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.McpTestSupport;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static com.copenhagen.mcp.qa.LocalCartQaIntegrationTest.call;
import static com.copenhagen.mcp.qa.LocalCartQaIntegrationTest.client;
import static com.copenhagen.mcp.qa.LocalCartQaIntegrationTest.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalHandoffQaIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void qa4McpRoundTripAndNewSessionReuseReceiptWithCspAndNoPaymentTools() throws Exception {
        Path catalog = directory.resolve("catalog.json");
        Files.write(catalog, McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("format", 1, "mode", "QA",
                "products", List.of(QaCoreServerTest.product("fixture-1", "group", 1000)))));
        System.setProperty("copenhagen.qa.catalog", catalog.toString());
        System.setProperty("copenhagen.qa.cart", directory.resolve("cart.json").toString());
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(McpServerApplication.class,
                LocalCartQa.Configuration.class).run("--server.address=127.0.0.1", "--server.port=0",
                        "--mcp.ui.enabled=true", "--mcp.catalog.enabled=true", "--mcp.catalog.detail-enabled=true",
                        "--mcp.catalog.required-scopes=qa:catalog:read", "--mcp.cart.enabled=true",
                        "--mcp.cart.read-scopes=qa:cart:read", "--mcp.cart.write-scopes=qa:cart:write",
                        "--mcp.handoff.enabled=true", "--mcp.handoff.write-scopes=qa:handoff:write",
                        "--mcp.handoff.link-base=" + QaPurchaseHandoff.LINK_BASE,
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access", "--spring.main.banner-mode=off")) {
            String base = McpTestSupport.baseUrl(app);
            Map<String, Object> request;
            Map<String, Object> receipt;
            try (McpSyncClient client = client(base)) {
                assertEquals(10, client.listTools().tools().size());
                Map<String, Object> cart = map(call(client, "get_cart", Map.of()).get("cart"));
                assertEquals("APPLIED", call(client, "add_cart_item", Map.of("cartId", cart.get("cartId"),
                        "expectedVersion", cart.get("version"), "idempotencyKey", "cart-request-0001",
                        "productId", "fixture-1", "merchantId", "qa-a", "optionId", "", "quantity", 1)).get("status"));
                cart = map(call(client, "get_cart", Map.of()).get("cart"));
                request = Map.of("cartId", cart.get("cartId"), "expectedVersion", cart.get("version"),
                        "idempotencyKey", "handoff-request-0001");
                receipt = call(client, "prepare_purchase_handoff", request);
                assertEquals("READY", receipt.get("status"));
                assertFalse(receipt.containsKey("publicUserRef"));
                McpSchema.Tool display = client.listTools().tools().stream()
                        .filter(tool -> tool.name().equals("open_copenhagen")).findFirst().orElseThrow();
                String uri = (String) display.meta().get("openai/outputTemplate");
                var resource = (McpSchema.TextResourceContents) client
                        .readResource(new McpSchema.ReadResourceRequest(uri)).contents().getFirst();
                assertTrue(resource.text().contains("data-handoff-enabled=\"true\""));
                assertEquals(List.of("https://developers.openai.com"),
                        map(resource.meta().get("openai/widgetCSP")).get("redirect_domains"));
            }
            try (McpSyncClient client = client(base)) {
                assertEquals(receipt, call(client, "prepare_purchase_handoff", request));
            }
        } finally {
            System.clearProperty("copenhagen.qa.catalog");
            System.clearProperty("copenhagen.qa.cart");
        }
    }
}
