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
import org.springframework.boot.test.context.TestComponent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로컬 브라우저 QA에서도 별도 HTTP MCP 클라이언트로 왕복한다. */
@RestController
@TestComponent
final class QaPreviewController {

    @GetMapping(value = "/qa", produces = "text/html;charset=UTF-8")
    String preview() throws IOException {
        String html = new ClassPathResource("mcp-ui/home.html").getContentAsString(StandardCharsets.UTF_8);
        String bridge = """
                <script>
                window.openai = {
                  callTool: async function(name) {
                    if (name !== 'check_connection') throw new Error('Unsupported QA tool');
                    const response = await fetch('/qa/check', {cache: 'no-store'});
                    if (!response.ok) throw new Error('QA server unavailable');
                    return response.json();
                  }
                };
                </script>
                """;
        return html.replace("<body>", "<body>" + bridge);
    }

    @GetMapping("/qa/check")
    Object check(HttpServletRequest request) {
        try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + request.getLocalPort()).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(5)).build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("check_connection").arguments(Map.of()).build());
            return Map.of("isError", Boolean.TRUE.equals(result.isError()),
                    "structuredContent", result.structuredContent());
        }
    }
}
