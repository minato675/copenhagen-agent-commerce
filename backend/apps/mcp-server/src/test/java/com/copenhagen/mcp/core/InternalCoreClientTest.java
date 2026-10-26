package com.copenhagen.mcp.core;

import com.copenhagen.mcp.auth.McpIdentity;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalCoreClientTest {
    private HttpServer server;
    private InternalCoreClient client;
    private final AtomicReference<String> seen = new AtomicReference<>();
    private int status = 200;
    private String response = "{}";
    private final McpIdentity identity = new McpIdentity("user", "connection", Set.of("cart:write"),
            Instant.now().plusSeconds(300), "fixture-agent");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + exchange.getRequestHeaders().getFirst("X-Agent-Authorization") + " "
                    + exchange.getRequestHeaders().getFirst("Idempotency-Key") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            UUID.fromString(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var properties = new CoreProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 65536);
        client = new InternalCoreClient(properties, HttpClient.newHttpClient(), () -> "fixture-service",
                McpJsonDefaults.getMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void exchange_writes_preservesKeyAndBothCredentials() {
        String key = UUID.randomUUID().toString();
        for (String method : new String[] {"POST", "PATCH", "DELETE"}) {
            client.exchange(identity, method, "/internal/v1/carts/current/items/item-1",
                    method.equals("DELETE") ? Map.of("cartVersion", 3) : Map.of(),
                    method.equals("DELETE") ? null : Map.of("cartVersion", 3, "quantity", 2), key);
            assertTrue(seen.get().contains("Bearer fixture-service Bearer fixture-agent " + key));
            assertTrue(seen.get().startsWith(method + " /internal/v1/"));
            if (method.equals("DELETE")) {
                assertTrue(seen.get().contains("?cartVersion=3"));
                assertTrue(seen.get().endsWith(key + " "));
            }
        }
    }

    @Test
    void exchange_read_encodesQueryWithoutChangingDestination() {
        client.exchange(identity, "GET", "/internal/v1/catalog/products", Map.of("query", "삼성 & 신발"), null, null);
        assertTrue(seen.get().contains("%26"));
        assertTrue(seen.get().contains("%EC%82%BC"));
    }

    @Test
    void exchange_realCatalogId_reachesCoreWithoutAllowingPathInjection() {
        client.exchange(identity, "GET", "/internal/v1/catalog/products/merchant-a/kurly:1000025270",
                Map.of(), null, null);
        assertTrue(seen.get().contains("/merchant-a/kurly:1000025270"));
        for (String unsafe : new String[] {"../admin", "%2e%2e", "id?admin=true", "id#fragment", "id\\admin"}) {
            assertThrows(IllegalArgumentException.class, () -> client.exchange(identity, "GET",
                    "/internal/v1/catalog/products/merchant-a/" + unsafe, Map.of(), null, null));
        }
    }

    @Test
    void exchange_conflict_isNotAppliedButServerFailureIsUnknown() {
        status = 409;
        response = "{\"error\":{\"code\":\"CART_VERSION_CONFLICT\"}}";
        var conflict = assertThrows(CoreApiException.class, () -> client.exchange(identity, "POST",
                "/internal/v1/carts/current/items", Map.of(), Map.of(), UUID.randomUUID().toString()));
        assertEquals("NOT_APPLIED", ((Map<?, ?>) conflict.body().get("error")).get("outcome"));
        assertEquals("REFETCH_CART", ((Map<?, ?>) conflict.body().get("error")).get("nextAction"));
        status = 500;
        var unknown = assertThrows(CoreApiException.class, () -> client.exchange(identity, "POST",
                "/internal/v1/carts/current/items", Map.of(), Map.of(), UUID.randomUUID().toString()));
        assertEquals("UNKNOWN", ((Map<?, ?>) unknown.body().get("error")).get("outcome"));
    }
}
