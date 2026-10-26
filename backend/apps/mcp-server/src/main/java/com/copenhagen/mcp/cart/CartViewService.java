package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.auth.McpIdentity;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.copenhagen.mcp.cart.CartService.invalid;
import static com.copenhagen.mcp.cart.CartService.map;
import static com.copenhagen.mcp.cart.CartService.number;

public final class CartViewService {

    private final CoreCartView core;
    private final CartService boundary;

    public CartViewService(CoreCartView core, CartService boundary) {
        this.core = core;
        this.boundary = boundary;
    }

    public Map<String, Object> get(McpIdentity identity, Map<String, Object> request) {
        boundary.validateInput(request, CartViewSchemas.INPUT);
        Map<String, Object> data = boundary.checked(identity, core.get(identity), CartViewSchemas.CORE);
        Map<String, Object> cart = map(data.get("cart"));
        CartService.validateCart(cart);
        List<Map<String, Object>> lines = CartService.lines(cart);
        List<Map<String, Object>> groups = maps(data.get("groups"));
        Set<List<Object>> keys = new HashSet<>();
        for (Map<String, Object> group : groups) {
            if (!keys.add(List.of(group.get("merchantId"), group.get("currency")))) {
                throw invalid();
            }
            List<Map<String, Object>> selected = lines.stream().filter(line ->
                    line.get("merchantId").equals(group.get("merchantId"))
                            && line.get("currency").equals(group.get("currency"))).toList();
            if (selected.isEmpty()) {
                throw invalid();
            }
            validateQuote(group);
            if (!group.get("status").equals("FAILED")
                    && !sameAmount(sum(selected, "lineTotal"), group.get("itemSubtotal"))) {
                throw invalid();
            }
            if (group.get("status").equals("CONFIRMED")
                    && selected.stream().anyMatch(line -> !line.get("priceStatus").equals("CONFIRMED"))) {
                throw invalid();
            }
        }
        if (lines.stream().anyMatch(line -> !keys.contains(List.of(line.get("merchantId"), line.get("currency"))))) {
            throw invalid();
        }
        Set<Object> currencies = new HashSet<>();
        for (Map<String, Object> total : maps(data.get("totals"))) {
            if (!currencies.add(total.get("currency"))) {
                throw invalid();
            }
            List<Map<String, Object>> selected = groups.stream()
                    .filter(group -> group.get("currency").equals(total.get("currency"))).toList();
            if (selected.isEmpty()) {
                throw invalid();
            }
            validateQuote(total);
            for (String field : List.of("itemSubtotal", "discount", "shipping", "total")) {
                if (!sameAmount(sum(selected, field), total.get(field))) {
                    throw invalid();
                }
            }
            String status = selected.stream().allMatch(group -> group.get("status").equals("CONFIRMED"))
                    ? "CONFIRMED" : selected.stream().anyMatch(group -> List.of("UNKNOWN", "FAILED")
                            .contains(group.get("status"))) ? "UNKNOWN" : "ESTIMATED";
            if (!total.get("status").equals(status)) {
                throw invalid();
            }
        }
        if (groups.stream().anyMatch(group -> !currencies.contains(group.get("currency")))) {
            throw invalid();
        }
        long failures = groups.stream().filter(group -> group.get("status").equals("FAILED")).count();
        String status = lines.isEmpty() ? "EMPTY" : failures == groups.size() ? "UNAVAILABLE" : failures > 0 ? "PARTIAL"
                : groups.stream().anyMatch(group -> group.get("status").equals("UNKNOWN")) ? "INCOMPLETE"
                : groups.stream().allMatch(group -> group.get("status").equals("CONFIRMED"))
                        ? "CONFIRMED" : "ESTIMATED";
        if (!data.get("status").equals(status)) {
            throw invalid();
        }
        return data;
    }

    private static void validateQuote(Map<String, Object> quote) {
        if (quote.get("status").equals("FAILED")) {
            if (List.of("itemSubtotal", "discount", "shipping", "total").stream()
                    .anyMatch(field -> quote.get(field) != null)) {
                throw invalid();
            }
            return;
        }
        boolean unknown = quote.get("itemSubtotal") == null || quote.get("discount") == null
                || quote.get("shipping") == null;
        if (unknown != quote.get("status").equals("UNKNOWN") || unknown != (quote.get("total") == null)) {
            throw invalid();
        }
        if (!unknown && number(quote.get("total")) != number(quote.get("itemSubtotal"))
                - number(quote.get("discount")) + number(quote.get("shipping"))) {
            throw invalid();
        }
    }

    private static Long sum(List<Map<String, Object>> values, String field) {
        if (values.stream().anyMatch(value -> value.get(field) == null)) {
            return null;
        }
        long amount = values.stream().mapToLong(value -> number(value.get(field))).sum();
        if (amount > CartSchemas.MAX_AMOUNT) {
            throw invalid();
        }
        return amount;
    }

    private static boolean sameAmount(Long left, Object right) {
        return Objects.equals(left, right == null ? null : number(right));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
