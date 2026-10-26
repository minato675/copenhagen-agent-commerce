package com.copenhagen.mcp.core;

import com.copenhagen.mcp.FakeCore;
import com.copenhagen.mcp.McpTestSupport;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CoreClientIntegrationTest {

    private FakeCore server;
    private HttpClient httpClient;
    private CoreClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeCore();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        client = new CoreClient(httpClient,
                new CoreProperties(URI.create(server.baseUrl()), Duration.ofSeconds(1),
                        Duration.ofMillis(600), 1024),
                McpJsonDefaults.getMapper(), McpJsonDefaults.getSchemaValidator());
    }

    @AfterEach
    void tearDown() {
        server.close();
        httpClient.close();
    }

    @Test
    void post_validResponse_returnsValidatedDataWithoutForwardingCredentials() {
        Map<String, Object> result = client.post("/fixture/success", Map.of("value", "request"),
                McpTestSupport.VALUE_SCHEMA);
        assertEquals(Map.of("value", "confirmed"), result);
        assertEquals("{\"value\":\"request\"}", server.lastBody());
        assertNull(server.lastAuthorization());
        assertEquals(1, server.requestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"service-error", "unauthorized", "redirect"})
    void get_serviceFailure_returnsSafeErrorWithoutRedirectOrRetry(String scenario) {
        CoreClientException exception = assertThrows(CoreClientException.class,
                () -> client.get("/fixture/" + scenario, McpTestSupport.VALUE_SCHEMA));
        assertEquals(ToolErrorCode.CORE_SERVICE_ERROR, exception.code());
        assertFalse(exception.getMessage().contains(FakeCore.PRIVATE_MARKER));
        assertEquals(1, server.requestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-schema", "invalid-json", "empty", "null", "wrong-type", "oversized"})
    void get_invalidResponse_rejectsUntrustedContent(String scenario) {
        CoreClientException exception = assertThrows(CoreClientException.class,
                () -> client.get("/fixture/" + scenario, McpTestSupport.VALUE_SCHEMA));
        assertEquals(ToolErrorCode.CORE_INVALID_RESPONSE, exception.code());
        assertFalse(exception.getMessage().contains(FakeCore.PRIVATE_MARKER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "slow-body"})
    void post_responseStalls_timesOutWithoutRetrying(String scenario) {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            CoreClientException exception = assertThrows(CoreClientException.class,
                    () -> client.post("/fixture/" + scenario, Map.of(), McpTestSupport.VALUE_SCHEMA));
            assertEquals(ToolErrorCode.CORE_TIMEOUT, exception.code());
            assertEquals(1, server.requestCount());
        });
    }

    @Test
    void get_connectionRefused_returnsUnavailable() {
        server.close();
        CoreClientException exception = assertThrows(CoreClientException.class,
                () -> client.get("/fixture/success", McpTestSupport.VALUE_SCHEMA));
        assertEquals(ToolErrorCode.CORE_UNAVAILABLE, exception.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/", "//example.com/", "/../admin", "/%2e%2e/admin", "/x?url=y"})
    void get_untrustedPath_rejectsBeforeNetworkCall(String path) {
        assertThrows(IllegalArgumentException.class, () -> client.get(path, McpTestSupport.VALUE_SCHEMA));
        assertEquals(0, server.requestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///secret", "http://name:secret@localhost", "http://localhost/path",
        "http://localhost?token=secret", "http://localhost#fragment"})
    void configure_invalidOrigin_rejectsConfiguration(String baseUrl) {
        assertThrows(IllegalArgumentException.class, () -> new CoreProperties(URI.create(baseUrl),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1024));
    }
}
