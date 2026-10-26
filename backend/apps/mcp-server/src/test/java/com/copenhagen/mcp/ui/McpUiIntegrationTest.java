package com.copenhagen.mcp.ui;

import com.copenhagen.mcp.FakeMcpTokenVerifier;
import com.copenhagen.mcp.McpAuthTestConfiguration;
import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.McpTestSupport;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpUiIntegrationTest {

    private static ConfigurableApplicationContext application;
    private static McpSyncClient client;

    @BeforeAll
    static void setUp() {
        application = new SpringApplicationBuilder(McpServerApplication.class, McpAuthTestConfiguration.class)
                .run("--server.port=0", "--mcp.ui.enabled=true", "--spring.main.banner-mode=off",
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access");
        client = McpTestSupport.client(McpTestSupport.baseUrl(application));
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
    }

    @Test
    void listAndReadResource_openToolReferencesTheVersionedHtml() {
        List<McpSchema.Tool> tools = client.listTools().tools();
        assertEquals(2, tools.size());
        McpSchema.Tool open = tools.stream().filter(tool -> tool.name().equals("open_copenhagen"))
                .findFirst().orElseThrow();
        String uri = (String) ((Map<?, ?>) open.meta().get("ui")).get("resourceUri");
        assertTrue(uri.matches("ui://copenhagen/home-[a-f0-9]{16}\\.html"));
        assertEquals(uri, client.listResources().resources().getFirst().uri());
        McpSchema.TextResourceContents html = (McpSchema.TextResourceContents) client
                .readResource(new McpSchema.ReadResourceRequest(uri)).contents().getFirst();
        assertEquals("text/html;profile=mcp-app", html.mimeType());
        assertTrue(html.text().contains("COPENHAGEN"));
        McpSchema.Tool check = tools.stream().filter(tool -> tool.name().equals("check_connection"))
                .findFirst().orElseThrow();
        assertEquals(List.of("app"), ((Map<?, ?>) check.meta().get("ui")).get("visibility"));
        assertFalse(check.meta().containsKey("openai/outputTemplate"));
    }

    @Test
    void callTool_returnsFreshServerResultWithoutIdentity() {
        McpSchema.CallToolResult first = call("open_copenhagen", Map.of());
        McpSchema.CallToolResult second = call("check_connection", Map.of());
        assertFalse(first.isError());
        assertFalse(second.isError());
        Map<?, ?> data = (Map<?, ?>) second.structuredContent();
        assertEquals("connected", data.get("status"));
        assertEquals("copenhagen-mcp", data.get("service"));
        assertNotEquals(((Map<?, ?>) first.structuredContent()).get("requestId"), data.get("requestId"));
        assertEquals(5, data.size());
        assertEquals(application.getBean(McpHomeResource.class).version(), data.get("uiVersion"));
    }

    @Test
    void callTool_rejectsUnexpectedArguments() {
        McpSchema.CallToolResult result = call("check_connection", Map.of("userId", "another-user"));
        assertTrue(result.isError());
        assertEquals("INVALID_ARGUMENT",
                ((Map<?, ?>) ((Map<?, ?>) result.structuredContent()).get("error")).get("code"));
    }

    @Test
    void readResource_withoutAuthentication_isRejected() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(McpTestSupport.baseUrl(application) + "/mcp"))
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"jsonrpc":"2.0","id":1,"method":"resources/list","params":{}}
                            """)).build();
            assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    private McpSchema.CallToolResult call(String name, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder().name(name).arguments(arguments).build());
    }
}
