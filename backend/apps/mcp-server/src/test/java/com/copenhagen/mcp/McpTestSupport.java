package com.copenhagen.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;

public final class McpTestSupport {

    public static final Map<String, Object> VALUE_SCHEMA = Map.of(
            "type", "object", "properties", Map.of("value", Map.of("type", "string", "maxLength", 64)),
            "required", List.of("value"), "additionalProperties", false);

    private McpTestSupport() {
    }

    public static String baseUrl(ConfigurableApplicationContext application) {
        return "http://127.0.0.1:" + application.getEnvironment().getRequiredProperty("local.server.port");
    }

    public static McpSyncClient client(String baseUrl) {
        return client(baseUrl, FakeMcpTokenVerifier.DEFAULT_TOKEN);
    }

    public static McpSyncClient client(String baseUrl, String token) {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl).endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token)).build())
                .requestTimeout(Duration.ofSeconds(15))
                .build();
    }
}
