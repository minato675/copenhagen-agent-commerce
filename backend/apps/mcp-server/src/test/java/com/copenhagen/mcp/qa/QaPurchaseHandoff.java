package com.copenhagen.mcp.qa;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Core 인계 계산 fixture. 저장은 QaCartStore의 동일 lock/원자 파일 교체 안에서 수행한다. */
final class QaPurchaseHandoff {
    static final String LINK_BASE = "https://developers.openai.com/plugins";

    private QaPurchaseHandoff() {
    }

    static Map<String, Object> prepare(Map<String, Object> cart, Map<String, Object> receipts,
            String connection, Map<String, Object> request, Instant now) {
        String key = connection + ":" + request.get("idempotencyKey");
        Map<String, Object> old = map(receipts.get(key));
        if (old != null && !sameRequest(map(old.get("request")), request)) {
            return result(request, "CONFLICT", "KEY_CONFLICT", now);
        }
        if (((Number) cart.get("version")).longValue() != ((Number) request.get("expectedVersion")).longValue()) {
            return result(request, "CONFLICT", "VERSION_CONFLICT", now);
        }
        if (old != null) {
            if (old.get("status").equals("READY") && !Instant.parse((String) old.get("expiresAt")).isAfter(now)) {
                return result(request, "EXPIRED", "EXPIRED", now);
            }
            return old;
        }
        if (receipts.size() >= 1000) {
            return result(request, "REJECTED", "UNAVAILABLE", now);
        }
        List<Map<String, Object>> lines = QaCatalogStore.maps(cart.get("lines"));
        Map<String, Object> output;
        if (lines.isEmpty()) {
            output = result(request, "REJECTED", "EMPTY_CART", now);
        } else if (lines.stream().map(line -> line.get("merchantId")).distinct().count() != 1) {
            output = result(request, "REJECTED", "MULTIPLE_MERCHANTS", now);
        } else {
            output = result(request, "READY", "NONE", now);
            String ref = "purchase-" + UUID.randomUUID();
            output.put("purchaseRef", ref);
            output.put("expiresAt", now.plusSeconds(600).toString());
            output.put("appLink", LINK_BASE + "#" + ref);
        }
        receipts.put(key, output);
        return output;
    }

    private static boolean sameRequest(Map<String, Object> left, Map<String, Object> right) {
        return left.get("cartId").equals(right.get("cartId"))
                && left.get("idempotencyKey").equals(right.get("idempotencyKey"))
                && ((Number) left.get("expectedVersion")).longValue()
                == ((Number) right.get("expectedVersion")).longValue();
    }

    private static Map<String, Object> result(Map<String, Object> request, String status, String reason, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "purchase.handoff");
        data.put("mode", "QA");
        data.put("request", request);
        data.put("status", status);
        data.put("reason", reason);
        data.put("purchaseRef", null);
        data.put("expiresAt", null);
        data.put("appLink", null);
        data.put("nextAction", status.equals("READY") ? "OPEN_APP" : "REVIEW_CART");
        data.put("checkedAt", now.toString());
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
