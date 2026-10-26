package com.copenhagen.mcp.qa;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Local preview -> MCP HTTP -> Mock Core HTTP. 브라우저에 QA 인증 값을 공개하지 않는다. */
@RestController
@TestComponent
final class CatalogPreviewController {

    private final boolean cart;
    private final boolean handoff;

    CatalogPreviewController() {
        this(false);
    }

    CatalogPreviewController(boolean cart) {
        this(cart, false);
    }

    CatalogPreviewController(boolean cart, boolean handoff) {
        this.cart = cart;
        this.handoff = handoff;
    }

    @GetMapping(value = "/qa", produces = "text/html;charset=UTF-8")
    String preview() throws IOException {
        String html = new ClassPathResource("mcp-ui/catalog.html").getContentAsString(StandardCharsets.UTF_8);
        String bridge = """
                <script>
                document.body.dataset.localPreview = 'true';
                window.openai = {
                  openExternal: function({href}) {
                    const link = document.createElement('a'); link.href = href;
                    link.target = '_blank'; link.rel = 'noopener noreferrer'; link.click();
                  },
                  callTool: async function(name, args) {
                    const response = await fetch('/qa/tools', {method: 'POST', cache: 'no-store',
                      headers: {'Content-Type': 'application/json'}, body: JSON.stringify({name, arguments: args})});
                    if (!response.ok) throw new Error('QA server unavailable');
                    return response.json();
                  }
                };
                </script>
                """;
        return html.replace("__CART_ENABLED__", Boolean.toString(cart))
                .replace("__HANDOFF_ENABLED__", Boolean.toString(handoff))
                .replace("__HANDOFF_LINK_BASE__", handoff ? QaPurchaseHandoff.LINK_BASE : "")
                .replace("<script>/* BRIDGE */</script>", bridge);
    }

    @GetMapping("/qa/check")
    Object check(HttpServletRequest request) {
        return invoke(request, "check_connection", Map.of());
    }

    @PostMapping("/qa/tools")
    Object tool(HttpServletRequest request, @RequestBody Call call) {
        if (!Set.of("search_products", "display_products", "open_copenhagen", "get_product_detail")
                .contains(call.name()) && !(cart && Set.of("get_cart", "add_cart_item", "update_cart_item",
                        "delete_cart_item").contains(call.name()))
                && !(handoff && "prepare_purchase_handoff".equals(call.name()))) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        return invoke(request, call.name(), call.arguments());
    }

    private Object invoke(HttpServletRequest request, String name, Map<String, Object> arguments) {
        try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + request.getLocalPort()).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(7)).build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name(name).arguments(arguments).build());
            return Map.of("isError", Boolean.TRUE.equals(result.isError()),
                    "structuredContent", result.structuredContent() == null ? Map.of() : result.structuredContent());
        }
    }

    record Call(String name, Map<String, Object> arguments) {
    }
}
