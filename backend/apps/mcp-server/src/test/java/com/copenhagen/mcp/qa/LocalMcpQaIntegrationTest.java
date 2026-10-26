package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.FakeMcpTokenVerifier;
import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.McpTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMcpQaIntegrationTest {

    private static ConfigurableApplicationContext application;
    private static HttpClient http;
    private static String baseUrl;

    @BeforeAll
    static void setUp() {
        application = new SpringApplicationBuilder(McpServerApplication.class, LocalMcpQa.QaConfiguration.class)
                .run("--server.address=127.0.0.1", "--server.port=0", "--mcp.ui.enabled=true",
                        "--mcp.auth.issuer=" + FakeMcpTokenVerifier.ISSUER,
                        "--mcp.auth.resource=" + FakeMcpTokenVerifier.RESOURCE,
                        "--mcp.auth.required-scopes=mcp:access", "--spring.main.banner-mode=off");
        baseUrl = McpTestSupport.baseUrl(application);
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() {
        if (application != null) {
            application.close();
        }
        if (http != null) {
            http.close();
        }
    }

    @Test
    void preview_roundTripsThroughMcpAndReturnsNoPersonalData() throws Exception {
        HttpResponse<String> result = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/qa/check"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertTrue(result.body().contains("copenhagen-mcp"));
        assertTrue(result.body().contains("connected"));
        assertFalse(result.body().contains("qa-user"));
        assertEquals("connection-only", result.headers().firstValue("X-Copenhagen-Qa").orElseThrow());
    }

    @Test
    void preview_foreignOriginAndUnknownRoutes_areRejected() throws Exception {
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/qa/check"))
                .header("Origin", "https://untrusted.example").GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void qa_doesNotAcceptUserCredentials() throws Exception {
        assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Authorization", "Bearer ignored-user-credential")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
