package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.McpServerApplication;
import com.copenhagen.mcp.McpTestSupport;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreIntegrationTransportTest {
    private static final String RESOURCE = "https://mcp.example/mcp";
    private static HttpServer core;
    private static ECKey key;
    private static Path privateKey;
    private static ConfigurableApplicationContext application;
    private static final AtomicReference<String> delegated = new AtomicReference<>();
    private static final AtomicReference<String> service = new AtomicReference<>();
    private static final AtomicReference<String> historyQuery = new AtomicReference<>();
    private static final AtomicReference<String> historyBody = new AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicInteger historyStatus =
            new java.util.concurrent.atomic.AtomicInteger(200);

    @org.junit.jupiter.api.BeforeEach
    void resetHistoryResponse() {
        historyStatus.set(200);
        historyBody.set("""
                {"periodStart":"2026-03-22T00:00:00Z","items":[],"nextCursor":null}
                """);
    }

    @BeforeAll
    static void start() throws Exception {
        key = new ECKeyGenerator(Curve.P_256).keyID("integration-test").generate();
        privateKey = Files.createTempFile("mcp-test-key-", ".jwk");
        Files.writeString(privateKey, key.toJSONString());
        core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        core.createContext("/jwks", exchange -> reply(exchange, new JWKSet(key.toPublicJWK()).toString()));
        core.createContext("/token", exchange -> reply(exchange,
                "{\"access_token\":\"test-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        core.createContext("/internal/v1/catalog/products", exchange -> {
            delegated.set(exchange.getRequestHeaders().getFirst("X-Agent-Authorization"));
            service.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, """
                    {"products":[],"filters":{"categories":[],"merchants":[]},
                     "nextCursor":null,"failedMerchants":[],"checkedAt":"2026-09-17T00:00:00Z"}
                    """);
        });
        core.createContext("/internal/v1/purchase-requests", exchange -> reply(exchange, """
                {"purchaseRequestId":"request1","status":"PENDING_ADDRESS","cartId":"cart1",
                 "cartVersion":12,"expiresAt":"%s","nextAction":"SELECT_SHIPPING_ADDRESS"}
                """.formatted(Instant.now().plusSeconds(1800))));
        core.createContext("/internal/v1/purchase-history", exchange -> {
            historyQuery.set(exchange.getRequestURI().getRawQuery());
            delegated.set(exchange.getRequestHeaders().getFirst("X-Agent-Authorization"));
            service.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, historyStatus.get(), historyBody.get());
        });
        core.createContext("/internal/v1/carts/current", exchange -> reply(exchange, """
                {"cartId":"cart1","version":1,"items":[],"merchantSummaries":[],
                 "summary":{"currency":"KRW","itemSubtotalAmount":0,"discountAmount":null,
                 "shippingAmount":null,"totalAmount":null,"status":"INCOMPLETE"}}
                """));
        core.start();
        String base = "http://127.0.0.1:" + core.getAddress().getPort();
        application = new SpringApplicationBuilder(McpServerApplication.class)
                .profiles("core-integration")
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--mcp.diagnostics.enabled=true",
                        "--mcp.diagnostics.admin-user-refs=00000000-0000-0000-0000-000000000001",
                        "--mcp.server.allowed-hosts=127.0.0.1:*", "--mcp.auth.jwt.jwks-uri=" + base + "/jwks",
                        "--mcp.auth.issuer=https://core.example", "--mcp.auth.resource=" + RESOURCE,
                        "--services.core.base-url=" + base, "--services.core.auth.token-uri=" + base + "/token",
                        "--services.core.auth.private-jwk-file=" + privateKey);
    }

    @AfterAll
    static void stop() throws Exception {
        if (application != null) {
            application.close();
        }
        if (core != null) {
            core.stop(0);
        }
        if (privateKey != null) {
            Files.deleteIfExists(privateKey);
        }
    }

    @Test
    void invalidReferenceSchemaIsLoggedBeforeResolution() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.copenhagen.mcp.tool.WidgetActionTrace.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write purchase:handoff"))) {
            client.initialize();
            var result = client.callTool(McpSchema.CallToolRequest.builder().name("resolve_widget_action")
                    .arguments(Map.of("reference", "invalid-private-reference")).build());
            assertTrue(result.isError());
            String logs = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(logs.contains("reason=INPUT_SCHEMA_REJECTED"));
            assertFalse(logs.contains("invalid-private-reference"));
            assertFalse(logs.contains("reason=REFERENCE_NOT_FOUND"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void authenticatedTransport_exposesOnlyCoreToolsAndDelegatesSignedAgent() throws Exception {
        String token = token("catalog:read cart:read cart:write purchase:handoff");
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application), token)) {
            client.initialize();
            var tools = client.listTools().tools();
            var selectedArgs = Map.of("merchantId", "merchant-c", "merchantProductId", "onion-1");
            var prepared = client.callTool(McpSchema.CallToolRequest.builder().name("prepare_widget_action")
                    .arguments(Map.of("tool", "get_product_detail", "arguments", selectedArgs,
                            "intent", "show onion options")).build());
            assertFalse(prepared.isError());
            Map<?, ?> referenceData = (Map<?, ?>) ((Map<?, ?>) prepared.structuredContent()).get("data");
            var resolved = client.callTool(McpSchema.CallToolRequest.builder().name("resolve_widget_action")
                    .arguments(Map.of("reference", referenceData.get("reference"))).build());
            assertFalse(resolved.isError());
            var recovered = client.callTool(McpSchema.CallToolRequest.builder().name("resolve_widget_action")
                    .arguments(Map.of("intent", "show onion options")).build());
            assertFalse(recovered.isError());
            assertEquals(resolved.structuredContent(), recovered.structuredContent());
            var ambiguousInput = client.callTool(McpSchema.CallToolRequest.builder().name("resolve_widget_action")
                    .arguments(Map.of("intent", "show onion options", "reference", referenceData.get("reference")))
                    .build());
            assertTrue(ambiguousInput.isError());
            assertEquals(Map.of("tool", "get_product_detail", "arguments", selectedArgs),
                    ((Map<?, ?>) resolved.structuredContent()).get("data"));
            assertEquals(Set.of("search_products", "get_product_detail", "get_cart", "add_cart_item",
                    "update_cart_item", "delete_cart_item", "prepare_purchase_handoff", "open_copenhagen",
                    "display_products", "get_purchase_status", "resend_purchase_notification", "prepare_cart",
                    "prepare_widget_action", "resolve_widget_action", "search_products_batch",
                    "display_search_batch", "get_product_details_batch", "display_cart", "display_product_detail",
                    "get_purchase_history", "apply_cart_changes", "add_cart_items_batch", "start_response_timing",
                    "get_response_timing", "stop_response_timing"),
                    tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet()));
            var result = client.callTool(McpSchema.CallToolRequest.builder().name("open_copenhagen")
                    .arguments(Map.of("query", "삼성")).build());
            assertFalse(result.isError());
            Map<?, ?> diagnostics = (Map<?, ?>) result.meta().get("copenhagen/diagnostics");
            java.util.UUID.fromString((String) diagnostics.get("requestId"));
            assertTrue(((Number) diagnostics.get("serverMillis")).longValue() >= 0);
            assertFalse(diagnostics.toString().contains(token));
            assertEquals("Bearer " + token, delegated.get());
            assertEquals("Bearer test-service-token", service.get());
            assertFalse(result.structuredContent().toString().contains(token));
            var resources = client.listResources().resources();
            assertEquals(4, resources.size());
            for (var resource : resources) {
                var html = (McpSchema.TextResourceContents) client.readResource(
                        new McpSchema.ReadResourceRequest(resource.uri())).contents().getFirst();
                assertFalse(html.text().contains("__VIEW__"));
                assertFalse(html.text().contains("__HANDOFF_LINK_BASE__"));
                assertFalse(html.meta().toString().contains("redirect_domains"));
                assertFalse(html.text().contains("로컬 Mock 안내 시나리오"));
            }
            for (var tool : tools) {
                if (Set.of("search_products", "prepare_widget_action", "resolve_widget_action",
                        "search_products_batch", "get_product_details_batch", "get_cart", "prepare_cart",
                        "add_cart_item", "add_cart_items_batch", "update_cart_item",
                        "delete_cart_item", "apply_cart_changes",
                        "get_product_detail", "get_purchase_history", "start_response_timing",
                        "get_response_timing", "stop_response_timing").contains(tool.name())) {
                    assertFalse(tool.meta().containsKey("openai/outputTemplate"));
                    if (tool.meta().get("ui") instanceof Map<?, ?> ui) {
                        assertFalse(ui.containsKey("resourceUri"));
                    }
                    continue;
                }
                String view = switch (tool.name()) {
                    case "display_product_detail" -> "detail";
                    case "display_cart"
                            -> "cart";
                    case "prepare_purchase_handoff", "get_purchase_status", "resend_purchase_notification"
                            -> "purchase";
                    default -> "search";
                };
                String uri = (String) tool.meta().get("openai/outputTemplate");
                assertTrue(uri.startsWith("ui://copenhagen/" + view + "-"));
                assertEquals(uri, ((Map<?, ?>) tool.meta().get("ui")).get("resourceUri"));
                assertTrue(resources.stream().anyMatch(resource -> resource.uri().equals(uri)));
            }
        }
    }

    @Test
    void shoppingIdentity_canReadStaticCartTemplate() throws Exception {
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write"))) {
            client.initialize();
            var resource = client.listResources().resources().stream()
                    .filter(value -> value.uri().startsWith("ui://copenhagen/cart-")).findFirst().orElseThrow();
            var html = (McpSchema.TextResourceContents) client.readResource(
                    new McpSchema.ReadResourceRequest(resource.uri())).contents().getFirst();
            assertTrue(html.text().contains("data-view=\"cart\""));
        }
    }

    @Test
    void purchaseHistory_delegatesIdentityAndQueryWithoutUi() throws Exception {
        String agent = token("catalog:read cart:read cart:write purchase:handoff");
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application), agent)) {
            client.initialize();
            var tool = client.listTools().tools().stream()
                    .filter(value -> value.name().equals("get_purchase_history")).findFirst().orElseThrow();
            assertTrue(tool.annotations().readOnlyHint());
            assertFalse(tool.annotations().destructiveHint());
            assertFalse(tool.meta().containsKey("openai/outputTemplate"));
            assertTrue(tool.meta().get("securitySchemes").toString().contains("purchase:handoff"));
            var result = client.callTool(McpSchema.CallToolRequest.builder().name("get_purchase_history")
                    .arguments(Map.of("pageSize", 2, "cursor", "opaque+/=")).build());
            assertFalse(result.isError());
            assertEquals("purchase.history", ((Map<?, ?>) result.structuredContent()).get("kind"));
            assertTrue(historyQuery.get().contains("pageSize=2"));
            assertTrue(historyQuery.get().contains("cursor=opaque%2B%2F%3D"));
            assertEquals("Bearer " + agent, delegated.get());
            assertEquals("Bearer test-service-token", service.get());
        }
    }

    @Test
    void purchaseHistory_withoutScope_doesNotCallCore() throws Exception {
        historyQuery.set("not-called");
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write"))) {
            client.initialize();
            var result = client.callTool(McpSchema.CallToolRequest.builder().name("get_purchase_history")
                    .arguments(Map.of()).build());
            assertTrue(result.isError());
            assertTrue(result.meta().get("mcp/www_authenticate").toString().contains("purchase:handoff"));
            assertEquals("not-called", historyQuery.get());
        }
    }

    @Test
    void purchaseHistory_pagesAndNullIds_surviveTransport() throws Exception {
        historyBody.set("""
                {"periodStart":"2026-03-22T00:00:00Z","items":[{
                  "merchantId":"merchant-a","merchantName":"상점","merchantProductId":null,
                  "merchantVariantId":null,"productName":"양파","optionLabel":"",
                  "purchaseCount":2,"totalQuantity":3,"lastUnitPriceAmount":1200,
                  "currency":"KRW","lastPurchasedAt":"2026-09-20T00:00:00Z","privateField":"hidden"
                }],"nextCursor":"page-two+/="}
                """);
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write purchase:handoff"))) {
            client.initialize();
            var first = client.callTool(McpSchema.CallToolRequest.builder().name("get_purchase_history")
                    .arguments(Map.of("pageSize", 1)).build());
            assertFalse(first.isError());
            Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) first.structuredContent()).get("data");
            Map<?, ?> item = (Map<?, ?>) ((List<?>) data.get("items")).getFirst();
            assertTrue(item.containsKey("merchantProductId"));
            assertEquals(null, item.get("merchantProductId"));
            assertFalse(item.containsKey("privateField"));
            resetHistoryResponse();
            var last = client.callTool(McpSchema.CallToolRequest.builder().name("get_purchase_history")
                    .arguments(Map.of("pageSize", 1, "cursor", data.get("nextCursor"))).build());
            assertFalse(last.isError());
            assertTrue(historyQuery.get().contains("cursor=page-two%2B%2F%3D"));
            Map<?, ?> lastData = (Map<?, ?>) ((Map<?, ?>) last.structuredContent()).get("data");
            assertEquals(List.of(), lastData.get("items"));
            assertTrue(lastData.containsKey("nextCursor"));
            assertEquals(null, lastData.get("nextCursor"));
        }
    }

    @Test
    void purchaseHistory_coreErrors_doNotBecomeEmptyHistoryOrExposeBody() throws Exception {
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write purchase:handoff"))) {
            client.initialize();
            for (var scenario : Map.of(400, "INVALID_ARGUMENT", 403, "ACCESS_DENIED", 503, "CORE_SERVICE_ERROR")
                    .entrySet()) {
                historyStatus.set(scenario.getKey());
                historyBody.set("{\"code\":\"" + scenario.getValue() + "\",\"message\":\"private-debug-data\"}");
                var result = client.callTool(McpSchema.CallToolRequest.builder().name("get_purchase_history")
                        .arguments(Map.of("cursor", "invalid-cursor")).build());
                assertTrue(result.isError());
                assertTrue(result.structuredContent().toString().contains(scenario.getValue()));
                assertFalse(result.toString().contains("private-debug-data"));
                assertFalse(result.structuredContent().toString().contains("purchase.history"));
            }
            historyStatus.set(200);
            historyBody.set("{\"periodStart\":\"bad-date\",\"items\":[],\"nextCursor\":null}");
            var invalid = client.callTool(McpSchema.CallToolRequest.builder().name("get_purchase_history")
                    .arguments(Map.of()).build());
            assertTrue(invalid.isError());
            assertTrue(invalid.structuredContent().toString().contains("CORE_INVALID_RESPONSE"));
        }
    }

    @Test
    void purchase_withoutAppLink_returnsReceiptForPushApproval() throws Exception {
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write purchase:handoff"))) {
            client.initialize();
            var result = client.callTool(McpSchema.CallToolRequest.builder().name("prepare_purchase_handoff")
                    .arguments(Map.of("cartId", "cart1", "cartVersion", 12,
                            "idempotencyKey", java.util.UUID.randomUUID().toString())).build());
            assertFalse(result.isError());
            assertTrue(result.structuredContent().toString().contains("request1"));
            assertFalse(result.structuredContent().toString().contains("appLink"));
        }
    }

    @Test
    void prepareCart_fullGrant_readsWithoutChallenge() throws Exception {
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write"))) {
            client.initialize();
            for (String name : List.of("prepare_cart", "get_cart")) {
                var result = client.callTool(McpSchema.CallToolRequest.builder().name(name)
                        .arguments(Map.of()).build());
                assertFalse(result.isError());
                assertFalse(result.meta().containsKey("mcp/www_authenticate"));
                assertTrue(result.structuredContent().toString().contains("cart.view"));
            }
        }
    }

    @Test
    void shoppingConnection_rejectsPartialGrantWithCompleteConsentScopes() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            for (String scopes : List.of("catalog:read", "catalog:read cart:read")) {
                var denied = http.send(HttpRequest.newBuilder(URI.create(McpTestSupport.baseUrl(application) + "/mcp"))
                        .header("Authorization", "Bearer " + token(scopes))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, denied.statusCode());
                assertTrue(denied.body().contains("INSUFFICIENT_SCOPE"));
                String challenge = denied.headers().firstValue("WWW-Authenticate").orElseThrow();
                assertTrue(challenge.contains("scope=\"cart:read cart:write catalog:read\""));
                assertFalse(challenge.contains("purchase:handoff"));
            }
        }
    }

    @Test
    void discoveryAndUnauthenticatedRequest_advertiseCanonicalHttpsResource() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            String base = McpTestSupport.baseUrl(application);
            var discovery = http.send(HttpRequest.newBuilder(URI.create(base
                    + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, discovery.statusCode());
            assertTrue(discovery.body().contains(RESOURCE));
            assertTrue(discovery.body().contains("purchase:handoff"));
            var denied = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());
            assertTrue(denied.headers().firstValue("WWW-Authenticate").orElseThrow()
                    .contains("scope=\"cart:read cart:write catalog:read\""));
            assertTrue(denied.headers().firstValue("WWW-Authenticate").orElseThrow()
                    .contains("https://mcp.example/.well-known/oauth-protected-resource/mcp"));
        }
    }

    @Test
    void diagnosticSessionCapturesCoreCallThroughTransport() throws Exception {
        try (var client = McpTestSupport.client(McpTestSupport.baseUrl(application),
                token("catalog:read cart:read cart:write"))) {
            client.initialize();
            var started = client.callTool(new McpSchema.CallToolRequest("start_response_timing", Map.of()));
            assertFalse(started.isError());
            Object id = ((Map<?, ?>) started.structuredContent()).get("diagnosticSessionId");
            var cart = client.callTool(new McpSchema.CallToolRequest("get_cart",
                    Map.of("diagnosticSessionId", id)));
            assertFalse(cart.isError());
            var report = client.callTool(new McpSchema.CallToolRequest("get_response_timing",
                    Map.of("diagnosticSessionId", id)));
            assertFalse(report.isError());
            assertTrue(report.structuredContent().toString().contains("core_http"));
            assertTrue(report.structuredContent().toString().contains("get_cart"));
            assertFalse(report.structuredContent().toString().contains("test-service-token"));
            var stopped = client.callTool(new McpSchema.CallToolRequest("stop_response_timing",
                    Map.of("diagnosticSessionId", id)));
            assertFalse(stopped.isError());
            assertTrue(client.callTool(new McpSchema.CallToolRequest("get_response_timing",
                    Map.of("diagnosticSessionId", id))).isError());
        }
    }

    private static String token(String scopes) throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer("https://core.example").audience(RESOURCE)
                        .subject("00000000-0000-0000-0000-000000000001")
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                        .claim("connection_id", "test-connection").claim("scope", scopes).build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static void reply(HttpExchange exchange, String json) throws java.io.IOException {
        reply(exchange, 200, json);
    }

    private static void reply(HttpExchange exchange, int status, String json) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
