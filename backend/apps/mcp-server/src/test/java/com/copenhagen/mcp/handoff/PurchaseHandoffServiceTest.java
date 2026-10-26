package com.copenhagen.mcp.handoff;

import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PurchaseHandoffServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final McpIdentity USER = new McpIdentity("user-a", "connection-a", Set.of(), NOW.plusSeconds(5000));
    private static final Map<String, Object> INPUT = Map.of("cartId", "cart-a", "expectedVersion", 1,
            "idempotencyKey", "request-handoff-0001");
    private static final HandoffProperties PROPERTIES = new HandoffProperties(true, Set.of("handoff:write"),
            URI.create("https://example.com/purchase"));

    private static Map<String, Object> ready() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "purchase.handoff");
        result.put("mode", "QA");
        result.put("request", INPUT);
        result.put("status", "READY");
        result.put("reason", "NONE");
        result.put("purchaseRef", "purchase-a");
        result.put("appLink", "https://example.com/purchase#purchase-a");
        result.put("expiresAt", NOW.plusSeconds(600).toString());
        result.put("checkedAt", NOW.toString());
        result.put("nextAction", "OPEN_APP");
        return result;
    }

    private static Map<String, Object> envelope(Map<String, Object> data) {
        return Map.of("publicUserRef", "user-a", "connectionRef", "connection-a", "data", data);
    }

    private static PurchaseHandoffService service(CorePurchaseHandoff core) {
        return new PurchaseHandoffService(core, PROPERTIES, McpJsonDefaults.getSchemaValidator(),
                McpJsonDefaults.getMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void inputRejectsPriceUserUrlAndInvalidVersionBeforeCore() {
        AtomicInteger calls = new AtomicInteger();
        PurchaseHandoffService service = service((user, request) -> {
            calls.incrementAndGet();
            return envelope(ready());
        });
        for (String field : List.of("price", "publicUserRef", "appLink", "approvalId")) {
            Map<String, Object> input = new LinkedHashMap<>(INPUT);
            input.put(field, "injected");
            assertThrows(CoreClientException.class, () -> service.prepare(USER, input));
        }
        Map<String, Object> input = new LinkedHashMap<>(INPUT);
        input.put("expectedVersion", 1.5);
        assertThrows(CoreClientException.class, () -> service.prepare(USER, input));
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsCrossUserConnectionAndRequestEcho() {
        for (String field : List.of("publicUserRef", "connectionRef")) {
            Map<String, Object> wrong = new LinkedHashMap<>(envelope(ready()));
            wrong.put(field, "other");
            assertThrows(McpAuthException.class, () -> service((u, r) -> wrong).prepare(USER, INPUT));
        }
        Map<String, Object> wrong = ready();
        wrong.put("request", Map.of("cartId", "cart-a", "expectedVersion", 2,
                "idempotencyKey", "request-handoff-0001"));
        assertThrows(CoreClientException.class, () -> service((u, r) -> envelope(wrong)).prepare(USER, INPUT));
    }

    @Test
    void onlyExactConfiguredLinkAndPublicRefAreAllowed() {
        for (String link : List.of("https://evil.example/purchase#purchase-a", "javascript:alert(1)",
                "https://example.com:443/purchase#purchase-a", "https://user@example.com/purchase#purchase-a",
                "https://example.com/purchase?token=secret#purchase-a", "https://example.com/purchase#other",
                "https://example.com/other#purchase-a", "https://example.com/purchase/../other#purchase-a")) {
            Map<String, Object> wrong = ready();
            wrong.put("appLink", link);
            assertThrows(CoreClientException.class, () -> service((u, r) -> envelope(wrong)).prepare(USER, INPUT));
        }
        assertEquals(ready(), service((u, r) -> envelope(ready())).prepare(USER, INPUT));
    }

    @Test
    void expiredReadyIsReturnedWithoutAnActionableLink() {
        Map<String, Object> expired = ready();
        expired.put("checkedAt", NOW.minusSeconds(600).toString());
        expired.put("expiresAt", NOW.minusSeconds(1).toString());
        Map<String, Object> result = service((u, r) -> envelope(expired)).prepare(USER, INPUT);
        assertEquals("EXPIRED", result.get("status"));
        assertNull(result.get("appLink"));
    }

    @Test
    void rejectsContradictoryOrMalformedResponse() {
        for (Map.Entry<String, Object> field : Map.<String, Object>of("checkedAt", "invalid",
                "expiresAt", NOW.minusSeconds(1).toString(), "reason", "KEY_CONFLICT",
                "nextAction", "REVIEW_CART", "unexpectedPrivateField", "secret").entrySet()) {
            Map<String, Object> wrong = ready();
            wrong.put(field.getKey(), field.getValue());
            assertThrows(CoreClientException.class, () -> service((u, r) -> envelope(wrong)).prepare(USER, INPUT));
        }
    }

    @Test
    void unknownNeverBecomesSuccessOrAnAutomaticRetry() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> unknown = ready();
        unknown.put("status", "UNKNOWN");
        unknown.put("reason", "OUTCOME_UNKNOWN");
        unknown.put("nextAction", "RETRY_SAME_REQUEST");
        for (String key : List.of("purchaseRef", "appLink", "expiresAt")) {
            unknown.put(key, null);
        }
        assertEquals("UNKNOWN", service((u, r) -> {
            calls.incrementAndGet();
            return envelope(unknown);
        }).prepare(USER, INPUT).get("status"));
        assertEquals(1, calls.get());
    }

    @Test
    void explicitScopeAndFixedHttpsBaseAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> new HandoffProperties(true, Set.of(), null));
        for (String url : List.of("http://example.com", "https://example.com?next=evil",
                "https://example.com/#secret", "https://user@example.com", "https://example.com/a/../b")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new HandoffProperties(true, Set.of("handoff:write"), URI.create(url)));
        }
        new HandoffProperties(false, Set.of(), null);
    }
}
