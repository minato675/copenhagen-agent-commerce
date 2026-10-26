package com.copenhagen.mcp;

import com.copenhagen.mcp.auth.McpTokenVerifier;
import com.copenhagen.mcp.merchant.CoreMerchantDirectory;
import com.copenhagen.mcp.tool.McpToolDefinition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerApplicationIntegrationTest {

    @Test
    void application_defaultConfiguration_rejectsMcpAndKeepsHealthAvailable() throws Exception {
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(McpServerApplication.class)
                .run("--server.port=0", "--spring.main.banner-mode=off");
                HttpClient http = HttpClient.newHttpClient()) {
            String baseUrl = McpTestSupport.baseUrl(application);
            assertTrue(application.getBeansOfType(McpTokenVerifier.class).isEmpty());
            assertTrue(application.getBeansOfType(CoreMerchantDirectory.class).isEmpty());
            assertTrue(application.getBeansOfType(McpToolDefinition.class).isEmpty());
            HttpResponse<String> missing = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, missing.statusCode());
            HttpResponse<String> unconfigured = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                    .header("Authorization", "Bearer " + FakeMcpTokenVerifier.DEFAULT_TOKEN)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, unconfigured.statusCode());
            assertTrue(unconfigured.body().contains("AUTH_NOT_CONFIGURED"));
            HttpResponse<String> metadata = http.send(HttpRequest.newBuilder(
                    URI.create(baseUrl + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, metadata.statusCode());
            HttpResponse<String> health = http.send(HttpRequest.newBuilder(
                    URI.create(McpTestSupport.baseUrl(application) + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("UP"));
        }
    }
}
