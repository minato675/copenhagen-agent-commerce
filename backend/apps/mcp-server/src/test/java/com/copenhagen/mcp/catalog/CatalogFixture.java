package com.copenhagen.mcp.catalog;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CatalogFixture {

    private CatalogFixture() {
    }

    public static Map<String, Object> product() {
        return Map.of("productId", "qa-product-1", "name", "테스트 태블릿", "brand", "테스트 브랜드",
                "category", "태블릿", "imageUrl", "https://example.test/product.jpg",
                "offers", List.of(Map.of("merchantId", "qa-a", "merchantName", "가상몰 A", "price", 10000L,
                        "currency", "KRW", "availability", "UNKNOWN")));
    }

    public static Map<String, Object> search(ProductSearchRequest request, List<Map<String, Object>> products) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "catalog.search");
        data.put("mode", "QA");
        data.put("status", products.isEmpty() ? "EMPTY" : "OK");
        data.put("query", request.arguments());
        data.put("products", products);
        data.put("categories", List.of("태블릿"));
        data.put("merchants", List.of(Map.of("merchantId", "qa-a", "name", "가상몰 A")));
        data.put("failedMerchants", List.of());
        data.put("total", products.size());
        data.put("hasNext", false);
        data.put("checkedAt", "2026-09-16T00:00:00Z");
        return Map.copyOf(data);
    }

    public static Map<String, Object> envelope(String user, String connection, Map<String, Object> data) {
        return Map.of("publicUserRef", user, "connectionRef", connection, "data", data);
    }

    public static Map<String, Object> change(Map<String, Object> data, String key, Object value) {
        Map<String, Object> result = new HashMap<>(data);
        result.put(key, value);
        return result;
    }
}
