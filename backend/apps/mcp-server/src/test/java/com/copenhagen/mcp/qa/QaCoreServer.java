package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.cart.CartEditSchemas;
import com.copenhagen.mcp.cart.CartSchemas;
import com.copenhagen.mcp.cart.CartViewSchemas;
import com.copenhagen.mcp.cart.CoreCart;
import com.copenhagen.mcp.cart.CoreCartEdits;
import com.copenhagen.mcp.cart.CoreCartView;
import com.copenhagen.mcp.catalog.CatalogSchemas;
import com.copenhagen.mcp.catalog.CoreProductCatalog;
import com.copenhagen.mcp.catalog.CoreProductDetails;
import com.copenhagen.mcp.catalog.DetailSchemas;
import com.copenhagen.mcp.catalog.ProductDetailRequest;
import com.copenhagen.mcp.catalog.ProductSearchRequest;
import com.copenhagen.mcp.core.CoreClient;
import com.copenhagen.mcp.core.CoreProperties;
import com.copenhagen.mcp.handoff.CorePurchaseHandoff;
import com.copenhagen.mcp.handoff.HandoffSchemas;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** loopback HTTP로도 Core 경계를 통과하는 QA 어댑터. /fixture 경로는 실제 Core 계약이 아니다. */
final class QaCoreServer implements AutoCloseable {

    private final HttpServer server;
    private final HttpClient http;
    private final CoreClient client;

    QaCoreServer(QaCatalogStore store) throws IOException {
        this(store, null);
    }

    QaCoreServer(QaCatalogStore store, QaCartStore carts) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture/catalog/search", exchange -> search(exchange, store));
        server.createContext("/fixture/catalog/detail", exchange -> search(exchange, store));
        if (carts != null) {
            server.createContext("/fixture/cart/add", exchange -> cart(exchange, carts));
            server.createContext("/fixture/cart/view", exchange -> cart(exchange, carts));
            server.createContext("/fixture/cart/update", exchange -> cart(exchange, carts));
            server.createContext("/fixture/cart/delete", exchange -> cart(exchange, carts));
            server.createContext("/fixture/handoff/prepare", exchange -> cart(exchange, carts));
        }
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        client = new CoreClient(http, new CoreProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 262144),
                McpJsonDefaults.getMapper(), McpJsonDefaults.getSchemaValidator());
    }

    CoreProductCatalog adapter() {
        return (identity, request) -> client.post("/fixture/catalog/search", body(identity, request.arguments()),
                CatalogSchemas.CORE_SEARCH);
    }

    CoreProductDetails detailsAdapter() {
        return (identity, request) -> client.post("/fixture/catalog/detail", body(identity, request.arguments()),
                DetailSchemas.CORE);
    }

    CoreCart cartAdapter() {
        return (identity, request) -> client.post("/fixture/cart/add", body(identity, request), CartSchemas.CORE_ADD);
    }

    CoreCartView cartViewAdapter() {
        return identity -> client.post("/fixture/cart/view", body(identity, Map.of()), CartViewSchemas.CORE);
    }

    CoreCartEdits cartEditsAdapter() {
        return (identity, action, request) -> switch (action) {
            case "UPDATE" -> client.post("/fixture/cart/update", body(identity, request), CartEditSchemas.CORE_UPDATE);
            case "DELETE" -> client.post("/fixture/cart/delete", body(identity, request), CartEditSchemas.CORE_DELETE);
            default -> throw new IllegalArgumentException("Unsupported cart edit");
        };
    }

    CorePurchaseHandoff handoffAdapter() {
        return (identity, request) -> client.post("/fixture/handoff/prepare", body(identity, request),
                HandoffSchemas.CORE);
    }

    @SuppressWarnings("unchecked")
    private void cart(HttpExchange exchange, QaCartStore carts) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!exchange.getRequestMethod().equals("POST")
                    || !java.util.Set.of("/fixture/cart/add", "/fixture/cart/view",
                            "/fixture/cart/update", "/fixture/cart/delete", "/fixture/handoff/prepare")
                            .contains(path)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(8193);
            if (bytes.length > 8192) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            Map<String, Object> input = McpJsonDefaults.getMapper().readValue(bytes, new TypeRef<>() { });
            try {
                Map<String, Object> data = path.endsWith("/prepare")
                        ? carts.prepareHandoff((String) input.get("publicUserRef"), (String) input.get("connectionRef"),
                                (Map<String, Object>) input.get("arguments"), java.time.Instant.now())
                        : path.endsWith("/view") ? carts.view((String) input.get("publicUserRef"))
                        : carts.change((String) input.get("publicUserRef"),
                                path.substring(path.lastIndexOf('/') + 1).toUpperCase(java.util.Locale.ROOT),
                                (Map<String, Object>) input.get("arguments"));
                byte[] output = McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("publicUserRef",
                        input.get("publicUserRef"), "connectionRef", input.get("connectionRef"), "data", data));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, output.length);
                exchange.getResponseBody().write(output);
            } catch (SecurityException exception) {
                exchange.sendResponseHeaders(403, -1);
            } catch (RuntimeException | IOException exception) {
                exchange.sendResponseHeaders(503, -1);
            }
        }
    }

    private static Map<String, Object> body(McpIdentity identity, Map<String, Object> arguments) {
        return Map.of("publicUserRef", identity.publicUserRef(), "connectionRef", identity.connectionRef(),
                "arguments", arguments);
    }

    @SuppressWarnings("unchecked")
    private void search(HttpExchange exchange, QaCatalogStore store) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!exchange.getRequestMethod().equals("POST")
                    || !(path.equals("/fixture/catalog/search") || path.equals("/fixture/catalog/detail"))) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(8193);
            if (bytes.length > 8192) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            Map<String, Object> input = McpJsonDefaults.getMapper().readValue(bytes, new TypeRef<>() { });
            Map<String, Object> arguments = (Map<String, Object>) input.get("arguments");
            Map<String, Object> data;
            try {
                data = path.endsWith("/search") ? store.search(ProductSearchRequest.parse(arguments,
                        McpJsonDefaults.getSchemaValidator())) : store.detail(ProductDetailRequest.parse(arguments,
                        McpJsonDefaults.getSchemaValidator()));
            } catch (RuntimeException exception) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            byte[] result = McpJsonDefaults.getMapper().writeValueAsBytes(Map.of(
                    "publicUserRef", input.get("publicUserRef"), "connectionRef", input.get("connectionRef"),
                    "data", data));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        http.close();
    }
}
