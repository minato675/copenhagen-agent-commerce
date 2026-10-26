package com.copenhagen.mcp.qa;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QaCartQuoteTest {

    @Test
    void discountRoundsDownToHundredsAndFreeShippingUsesDiscountedMerchantSubtotal() {
        Map<String, Object> below = group(List.of(line("qa-a", "KRW", 31_400L)));
        assertEquals(1500L, below.get("discount"));
        assertEquals(3000L, below.get("shipping"));
        assertEquals(32_900L, below.get("total"));
        Map<String, Object> threshold = group(List.of(line("qa-a", "KRW", 31_500L)));
        assertEquals(1500L, threshold.get("discount"));
        assertEquals(0L, threshold.get("shipping"));
        assertEquals(30_000L, threshold.get("total"));
        assertEquals("ESTIMATED", threshold.get("status"));
    }

    @Test
    void linesShareMerchantShippingButDifferentMerchantsAreChargedSeparately() {
        Map<String, Object> view = view(List.of(line("qa-a", "KRW", 10_000L),
                line("qa-a", "KRW", 10_000L), line("qa-b", "KRW", 20_000L)), Set.of());
        Map<String, Object> total = QaCatalogStore.maps(view.get("totals")).getFirst();
        assertEquals(40_000L, total.get("itemSubtotal"));
        assertEquals(2000L, total.get("discount"));
        assertEquals(6000L, total.get("shipping"));
        assertEquals(44_000L, total.get("total"));
        assertEquals("ESTIMATED", view.get("status"));
    }

    @Test
    void missingPricesAndFailedMerchantsNeverBecomeZeroOrCompleteEstimates() {
        assertNull(group(List.of(line("qa-a", "KRW", null))).get("total"));
        assertNull(group(List.of(line("qa-a", "USD", 1000L))).get("shipping"));
        Map<String, Object> partial = view(List.of(line("qa-a", "KRW", 1000L),
                line("qa-b", "KRW", 2000L)), Set.of("qa-b"));
        assertEquals("PARTIAL", partial.get("status"));
        assertNull(QaCatalogStore.maps(partial.get("totals")).getFirst().get("total"));
        assertEquals("EMPTY", view(List.of(), Set.of()).get("status"));
    }

    private static Map<String, Object> line(String merchant, String currency, Long amount) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("merchantId", merchant);
        line.put("currency", currency);
        line.put("lineTotal", amount);
        return line;
    }

    private static Map<String, Object> view(List<Map<String, Object>> lines, Set<String> failed) {
        return QaCartQuote.view(Map.of("lines", lines), failed);
    }

    private static Map<String, Object> group(List<Map<String, Object>> lines) {
        return QaCatalogStore.maps(view(lines, Set.of()).get("groups")).getFirst();
    }
}
