package com.copenhagen.mcp.qa;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 사용자 요청에 따른 QA 전용 원화 할인·배송 규칙. 실제 판매처의 가격 정책이 아니다. */
final class QaCartQuote {

    private QaCartQuote() {
    }

    static Map<String, Object> view(Map<String, Object> cart, Set<String> failed) {
        List<Map<String, Object>> lines = QaCatalogStore.maps(cart.get("lines"));
        Map<List<String>, List<Map<String, Object>>> byMerchant = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            byMerchant.computeIfAbsent(List.of((String) line.get("merchantId"), (String) line.get("currency")),
                    key -> new ArrayList<>()).add(line);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<List<String>, List<Map<String, Object>>> group : byMerchant.entrySet()) {
            boolean unavailable = failed.contains(group.getKey().getFirst());
            Map<String, Object> quote = quote(group.getKey().get(1),
                    unavailable ? null : sum(group.getValue(), "lineTotal"));
            quote.put("merchantId", group.getKey().getFirst());
            if (unavailable) {
                quote.put("status", "FAILED");
            }
            groups.add(quote);
        }
        List<Map<String, Object>> totals = groups.stream().map(group -> (String) group.get("currency"))
                .distinct().map(currency -> aggregate(currency, groups.stream()
                        .filter(group -> group.get("currency").equals(currency)).toList())).toList();
        long failures = groups.stream().filter(group -> group.get("status").equals("FAILED")).count();
        String status = lines.isEmpty() ? "EMPTY" : failures == groups.size() ? "UNAVAILABLE"
                : failures > 0 ? "PARTIAL"
                : groups.stream().anyMatch(group -> group.get("status").equals("UNKNOWN"))
                        ? "INCOMPLETE" : "ESTIMATED";
        return Map.of("kind", "cart.view", "mode", "QA", "status", status, "cart", cart,
                "groups", groups, "totals", totals, "checkedAt", Instant.now().toString());
    }

    private static Map<String, Object> quote(String currency, Long subtotal) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("currency", currency);
        quote.put("status", "UNKNOWN");
        quote.put("itemSubtotal", subtotal);
        quote.put("discount", null);
        quote.put("shipping", null);
        quote.put("total", null);
        if (subtotal != null && currency.equals("KRW")) {
            long discount = subtotal / 2000L * 100L;
            long shipping = subtotal - discount >= 30_000L ? 0L : 3000L;
            quote.put("status", "ESTIMATED");
            quote.put("discount", discount);
            quote.put("shipping", shipping);
            quote.put("total", subtotal - discount + shipping);
        }
        return quote;
    }

    private static Map<String, Object> aggregate(String currency, List<Map<String, Object>> groups) {
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("currency", currency);
        total.put("status", groups.stream().allMatch(group -> group.get("status").equals("ESTIMATED"))
                ? "ESTIMATED" : "UNKNOWN");
        for (String field : List.of("itemSubtotal", "discount", "shipping", "total")) {
            total.put(field, sum(groups, field));
        }
        return total;
    }

    private static Long sum(List<Map<String, Object>> lines, String field) {
        return lines.stream().anyMatch(line -> line.get(field) == null) ? null
                : lines.stream().mapToLong(line -> ((Number) line.get(field)).longValue()).sum();
    }
}
