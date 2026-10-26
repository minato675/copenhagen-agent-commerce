package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.core.CoreApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PurchaseHistoryProjectionTest {
    @Test
    void projectsOnlyPublicFieldsAndPreservesNullableIds() {
        Map<String, Object> row = item();
        row.put("merchantProductId", null);
        row.put("merchantVariantId", null);
        row.put("userId", "private");
        Map<String, Object> page = page(row);
        page.put("address", "private");
        Map<String, Object> result = CommerceProjection.purchaseHistory(page);
        assertFalse(result.containsKey("address"));
        Map<?, ?> projected = (Map<?, ?>) ((List<?>) result.get("items")).get(0);
        assertFalse(projected.containsKey("userId"));
        assertEquals(11, projected.size());
        assertNull(projected.get("merchantProductId"));
        assertNull(projected.get("merchantVariantId"));
        assertEquals("opaque+/=", result.get("nextCursor"));
        assertEquals(1200L, projected.get("lastUnitPriceAmount"));
    }

    @Test
    void rejectsMissingRequiredFieldsIncludingNullableIds() {
        for (String field : item().keySet()) {
            Map<String, Object> row = item();
            row.remove(field);
            invalid(page(row));
        }
        for (String field : page(item()).keySet()) {
            Map<String, Object> source = page(item());
            source.remove(field);
            invalid(source);
        }
    }

    @Test
    void rejectsMalformedValuesRatherThanInventingHistory() {
        Map<String, Object> badValues = Map.ofEntries(
                Map.entry("merchantId", "bad/id"), Map.entry("merchantProductId", "bad/id"),
                Map.entry("merchantVariantId", "bad/id"), Map.entry("merchantName", 1),
                Map.entry("productName", 1), Map.entry("optionLabel", 1),
                Map.entry("purchaseCount", 0), Map.entry("totalQuantity", 1.5),
                Map.entry("lastUnitPriceAmount", -1), Map.entry("currency", "USD"),
                Map.entry("lastPurchasedAt", "yesterday"));
        badValues.forEach((field, value) -> {
            Map<String, Object> row = item();
            row.put(field, value);
            invalid(page(row));
        });
        Map<String, Object> source = page(item());
        source.put("periodStart", "today");
        invalid(source);
        source = page(item());
        source.put("nextCursor", 12);
        invalid(source);
        source = page(item());
        source.put("items", java.util.Collections.nCopies(51, item()));
        invalid(source);
    }

    private void invalid(Map<String, Object> source) {
        assertEquals("CORE_INVALID_RESPONSE", assertThrows(CoreApiException.class,
                () -> CommerceProjection.purchaseHistory(source)).getMessage());
    }

    private Map<String, Object> page(Map<String, Object> row) {
        return new LinkedHashMap<>(Map.of("periodStart", "2026-03-22T00:00:00Z",
                "items", List.of(row), "nextCursor", "opaque+/="));
    }

    private Map<String, Object> item() {
        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("merchantId", "merchant-a"), Map.entry("merchantName", "판매처"),
                Map.entry("merchantProductId", "product:1"), Map.entry("merchantVariantId", "variant:1"),
                Map.entry("productName", "양파"), Map.entry("optionLabel", ""),
                Map.entry("purchaseCount", 2), Map.entry("totalQuantity", 3L),
                Map.entry("lastUnitPriceAmount", 1200L), Map.entry("currency", "KRW"),
                Map.entry("lastPurchasedAt", "2026-09-20T00:00:00Z")));
    }
}
