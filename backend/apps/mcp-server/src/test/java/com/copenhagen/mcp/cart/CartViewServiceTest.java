package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.core.CoreClientException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.copenhagen.mcp.cart.CartServiceTest.USER;
import static com.copenhagen.mcp.cart.CartServiceTest.cart;
import static com.copenhagen.mcp.cart.CartServiceTest.change;
import static com.copenhagen.mcp.cart.CartServiceTest.envelope;
import static com.copenhagen.mcp.cart.CartServiceTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CartViewServiceTest {

    static Map<String, Object> quote(boolean merchant, String currency, Object amount) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (merchant) {
            result.put("merchantId", "qa-a");
        }
        result.put("currency", currency);
        result.put("itemSubtotal", amount);
        result.put("discount", null);
        result.put("shipping", null);
        result.put("total", null);
        result.put("status", "UNKNOWN");
        return result;
    }

    static Map<String, Object> view() {
        return Map.of("kind", "cart.view", "mode", "QA", "status", "INCOMPLETE", "cart", cart(line()),
                "groups", List.of(quote(true, "KRW", 2000)), "totals", List.of(quote(false, "KRW", 2000)),
                "checkedAt", "2026-09-16T00:00:00Z");
    }

    static Map<String, Object> get(Map<String, Object> data) {
        return new CartViewService(user -> envelope(data), CartServiceTest.service(null)).get(USER, Map.of());
    }

    @Test
    void view_preservesUnknownShippingDiscountAndFinalAmount() {
        Map<String, Object> data = get(view());
        Map<?, ?> total = (Map<?, ?>) ((List<?>) data.get("totals")).getFirst();
        assertEquals(2000, total.get("itemSubtotal"));
        assertNull(total.get("shipping"));
        assertNull(total.get("discount"));
        assertNull(total.get("total"));
    }

    @Test
    void view_emptyCartHasNoInventedQuote() {
        Map<String, Object> empty = change(change(change(change(view(), "cart",
                change(cart(line()), "lines", List.of())), "status", "EMPTY"), "groups", List.of()),
                "totals", List.of());
        assertEquals("EMPTY", get(empty).get("status"));
        assertThrows(CoreClientException.class, () -> get(change(empty, "totals", List.of(quote(false, "KRW", 0)))));
    }

    @Test
    void view_keepsCurrenciesSeparateAndRejectsMergedTotal() {
        Map<String, Object> usd = change(change(change(line(), "lineId", "usd-line"), "currency", "USD"),
                "productId", "usd-product");
        Map<String, Object> data = change(change(change(view(), "cart",
                change(cart(line()), "lines", List.of(line(), usd))),
                "groups", List.of(quote(true, "KRW", 2000), quote(true, "USD", 2000))),
                "totals", List.of(quote(false, "KRW", 2000), quote(false, "USD", 2000)));
        assertEquals(2, ((List<?>) get(data).get("totals")).size());
        assertThrows(CoreClientException.class, () -> get(change(data, "totals", List.of(quote(false, "KRW", 4000)))));
    }

    @Test
    void view_validatesPartialFailureAndDoesNotFillMissingAmountsWithZero() {
        Map<String, Object> failed = change(change(quote(true, "KRW", null), "merchantId", "qa-b"), "status", "FAILED");
        Map<String, Object> other = change(change(line(), "lineId", "line-b"), "merchantId", "qa-b");
        Map<String, Object> data = change(change(change(change(view(), "cart",
                change(cart(line()), "lines", List.of(line(), other))),
                "groups", List.of(quote(true, "KRW", 2000), failed)),
                "totals", List.of(quote(false, "KRW", null))), "status", "PARTIAL");
        assertEquals("PARTIAL", get(data).get("status"));
        assertThrows(CoreClientException.class, () -> get(change(data, "status", "ESTIMATED")));
        assertThrows(CoreClientException.class, () -> get(change(data, "totals", List.of(quote(false, "KRW", 2000)))));
    }

    @Test
    void view_checksArithmeticCoverageBoundsAndDuplicateGroups() {
        for (Map<String, Object> data : List.of(change(view(), "groups", List.of()),
                change(view(), "groups", List.of(quote(true, "KRW", 2000), quote(true, "KRW", 2000))),
                change(view(), "groups", List.of(quote(true, "KRW", 2001))),
                change(view(), "totals", List.of(quote(false, "KRW", CartSchemas.MAX_AMOUNT + 1))),
                change(view(), "totals", List.of(change(quote(false, "KRW", 2000), "total", 2000))))) {
            assertThrows(CoreClientException.class, () -> get(data));
        }
        Map<String, Object> huge = change(change(line(), "unitPrice", 1_000_000_000_000L),
                "lineTotal", 2_000_000_000_000L);
        List<Map<String, Object>> lines = java.util.stream.IntStream.range(0, 5).mapToObj(index ->
                change(change(huge, "lineId", "large-" + index), "productId", "product-" + index)).toList();
        assertThrows(CoreClientException.class,
                () -> get(change(view(), "cart", change(cart(line()), "lines", lines))));
    }
}
