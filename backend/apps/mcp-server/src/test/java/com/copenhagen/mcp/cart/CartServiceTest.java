package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartServiceTest {

    static final McpIdentity USER = new McpIdentity("user-a", "connection-a", Set.of(),
            Instant.parse("2100-01-01T00:00:00Z"));
    static final Map<String, Object> ADD = Map.of("cartId", "cart-a", "expectedVersion", 0,
            "idempotencyKey", "request-key-000001", "productId", "product-a", "merchantId", "qa-a",
            "optionId", "", "quantity", 2);

    static Map<String, Object> line() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineId", "line-a");
        line.put("productId", "product-a");
        line.put("merchantId", "qa-a");
        line.put("merchantName", "가상몰 A");
        line.put("name", "<script>상품 이름</script>");
        line.put("imageUrl", "");
        line.put("optionId", "");
        line.put("optionLabel", "");
        line.put("quantity", 2);
        line.put("unitPrice", 1000);
        line.put("lineTotal", 2000);
        line.put("currency", "KRW");
        line.put("priceStatus", "ESTIMATED");
        return line;
    }

    static Map<String, Object> cart(Map<String, Object> line) {
        return Map.of("cartId", "cart-a", "version", 1, "lines", List.of(line),
                "approvalState", "REVALIDATION_REQUIRED");
    }

    static Map<String, Object> result(Map<String, Object> cart) {
        return Map.of("kind", "cart.mutation", "mode", "QA", "action", "ADD", "request", ADD,
                "status", "APPLIED", "reason", "NONE", "cart", cart, "affectedLineId", "line-a",
                "checkedAt", "2026-09-16T00:00:00Z");
    }

    static Map<String, Object> envelope(Map<String, Object> result) {
        return Map.of("publicUserRef", "user-a", "connectionRef", "connection-a", "data", result);
    }

    static Map<String, Object> change(Map<String, Object> source, String key, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(source);
        changed.put(key, value);
        return changed;
    }

    static CartService service(CoreCart core) {
        return new CartService(core, McpJsonDefaults.getSchemaValidator(), McpJsonDefaults.getMapper());
    }

    @Test
    void add_validatesBeforeCallingCoreAndNeverAcceptsUserOrPriceInput() {
        AtomicInteger calls = new AtomicInteger();
        CartService service = service((user, input) -> {
            calls.incrementAndGet();
            return envelope(result(cart(line())));
        });
        for (Map<String, Object> bad : List.of(change(ADD, "quantity", 0), change(ADD, "quantity", 100),
                change(ADD, "quantity", 1.5), change(ADD, "expectedVersion", -1),
                change(ADD, "idempotencyKey", "short"), change(ADD, "publicUserRef", "someone"),
                change(ADD, "price", 1), change(ADD, "optionId", "https://evil"))) {
            assertEquals(ToolErrorCode.INVALID_ARGUMENT,
                    assertThrows(CoreClientException.class, () -> service.add(USER, bad)).code());
        }
        assertEquals(0, calls.get());
        assertEquals("APPLIED", service.add(USER, ADD).get("status"));
        assertEquals(1, calls.get());
    }

    @Test
    void add_rejectsDifferentOwnerOrConnectionAndMismatchedEcho() {
        for (String field : List.of("publicUserRef", "connectionRef")) {
            CartService service = service((user, request) -> change(envelope(result(cart(line()))), field, "other"));
            assertThrows(McpAuthException.class, () -> service.add(USER, ADD));
        }
        CartService service = service((user, request) -> envelope(change(result(cart(line())), "request",
                change(ADD, "quantity", 3))));
        assertThrows(CoreClientException.class, () -> service.add(USER, ADD));
    }

    @Test
    void add_requiresConfirmedLineCartVersionAndExactIntegerAmounts() {
        for (Map<String, Object> bad : List.of(change(cart(line()), "cartId", "other-cart"),
                change(cart(line()), "version", 2), cart(change(line(), "quantity", 1)),
                cart(change(line(), "lineTotal", 1999)), cart(change(line(), "imageUrl", "javascript:evil")),
                cart(change(line(), "unitPrice", 1_000_000_000_001L)),
                change(cart(line()), "lines", List.of(line(), change(line(), "lineId", "duplicate-selection"))))) {
            CartService service = service((user, request) -> envelope(result(bad)));
            assertEquals(ToolErrorCode.CORE_INVALID_RESPONSE,
                    assertThrows(CoreClientException.class, () -> service.add(USER, ADD)).code());
        }
        Map<String, Object> unknown = change(change(change(line(), "unitPrice", null), "lineTotal", null),
                "priceStatus", "UNKNOWN");
        assertEquals("APPLIED", service((user, request) -> envelope(result(cart(unknown))))
                .add(USER, ADD).get("status"));
    }

    @Test
    void add_neverRetriesTimeoutAndPreservesOriginalKeyAndBody() {
        AtomicInteger calls = new AtomicInteger();
        CartService service = service((user, request) -> {
            assertEquals(ADD, request);
            calls.incrementAndGet();
            throw new CoreClientException(ToolErrorCode.CORE_TIMEOUT);
        });
        assertEquals(ToolErrorCode.CORE_TIMEOUT,
                assertThrows(CoreClientException.class, () -> service.add(USER, ADD)).code());
        assertEquals(1, calls.get());
    }

    @Test
    void add_doesNotTurnConflictOrRejectionIntoSuccess() {
        Map<String, Object> conflict = change(change(change(result(cart(line())), "status", "CONFLICT"),
                "reason", "KEY_CONFLICT"), "affectedLineId", null);
        assertEquals("CONFLICT", service((user, request) -> envelope(conflict)).add(USER, ADD).get("status"));
        assertThrows(CoreClientException.class, () -> service((user, request) ->
                envelope(change(conflict, "status", "APPLIED"))).add(USER, ADD));
    }

    @Test
    void tools_requireExplicitWriteScopesAndAdvertiseMutation() {
        assertThrows(IllegalArgumentException.class, () -> new CartProperties(true, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CartProperties(true, Set.of("read"), Set.of("read")));
        assertThrows(IllegalArgumentException.class,
                () -> new CartProperties(true, Set.of("read", "shared"), Set.of("shared")));
        CartProperties extended = new CartProperties(true, Set.of("shared"), Set.of("shared", "write"));
        McpIdentity reader = new McpIdentity("user-a", "connection-a", extended.readScopes(), USER.expiresAt());
        assertThrows(McpAuthException.class, () -> reader.requireScopes(extended.writeScopes()));
        CartProperties properties = new CartProperties(true, Set.of("cart:read"), Set.of("cart:write"));
        CartToolConfiguration configuration = new CartToolConfiguration();
        com.copenhagen.mcp.tool.McpToolDefinition tool = configuration.addCartItem(null, properties);
        assertEquals(Set.of("cart:write"), tool.requiredScopes());
        assertFalse(tool.tool().annotations().readOnlyHint());
        assertTrue(tool.tool().annotations().idempotentHint());
    }
}
