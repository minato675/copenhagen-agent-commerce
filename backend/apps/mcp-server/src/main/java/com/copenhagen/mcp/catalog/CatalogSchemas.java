package com.copenhagen.mcp.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 공개 Tool 및 Core 어댑터 정규화 형식. 실제 Core API 계약과 구분한다. 금액은 KRW 정수이다. */
public final class CatalogSchemas {

    public static final long MAX_PRICE = 1_000_000_000_000L;
    public static final Map<String, Object> SEARCH_INPUT = searchInput();
    public static final Map<String, Object> OFFER = object(Map.of(
            "merchantId", identifier(), "merchantName", text(80),
            "price", nullable(integer(0, MAX_PRICE)), "currency", enumeration("KRW"),
            "availability", enumeration("IN_STOCK", "OUT_OF_STOCK", "DISCONTINUED", "UNKNOWN")));
    public static final Map<String, Object> PRODUCT = object(Map.of(
            "productId", identifier(), "name", text(240), "brand", text(120), "category", text(160),
            "imageUrl", text(2048), "offers", array(OFFER, 10)));
    public static final Map<String, Object> SEARCH_OUTPUT = searchOutput();
    public static final Map<String, Object> CORE_SEARCH = envelope(SEARCH_OUTPUT);

    private CatalogSchemas() {
    }

    public static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", Map.copyOf(properties),
                "required", properties.keySet().stream().sorted().toList(), "additionalProperties", false);
    }

    public static Map<String, Object> envelope(Map<String, Object> data) {
        return object(Map.of("publicUserRef", text(256), "connectionRef", text(256), "data", data));
    }

    public static Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "maxLength", maximum);
    }

    public static Map<String, Object> identifier() {
        return Map.of("type", "string", "pattern", "^[A-Za-z0-9][A-Za-z0-9:_-]{0,95}$");
    }

    public static Map<String, Object> enumeration(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    public static Map<String, Object> integer(long minimum, long maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    public static Map<String, Object> nullable(Map<String, Object> value) {
        return Map.of("anyOf", List.of(value, Map.of("type", "null")));
    }

    public static Map<String, Object> array(Map<String, Object> item, int maximum) {
        return Map.of("type", "array", "items", item, "maxItems", maximum, "uniqueItems", true);
    }

    private static Map<String, Object> searchInput() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", text(120));
        properties.put("category", text(160));
        properties.put("brand", text(120));
        properties.put("merchantId", Map.of("type", "string", "pattern", "^[A-Za-z0-9:_-]{0,96}$"));
        properties.put("minPrice", integer(0, MAX_PRICE));
        properties.put("maxPrice", integer(0, MAX_PRICE));
        properties.put("page", integer(1, 10000));
        properties.put("pageSize", integer(1, 20));
        properties.put("sort", enumeration("RELEVANCE", "PRICE_ASC", "PRICE_DESC"));
        return Map.of("type", "object", "properties", Map.copyOf(properties), "additionalProperties", false);
    }

    private static Map<String, Object> searchOutput() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kind", enumeration("catalog.search"));
        properties.put("mode", enumeration("QA", "LIVE"));
        properties.put("status", enumeration("OK", "EMPTY", "PARTIAL", "CLARIFY"));
        properties.put("query", SEARCH_INPUT);
        properties.put("products", array(PRODUCT, 20));
        properties.put("categories", array(text(160), 200));
        properties.put("merchants", array(object(Map.of("merchantId", identifier(), "name", text(80))), 10));
        properties.put("failedMerchants", array(identifier(), 10));
        properties.put("total", integer(0, 1_000_000));
        properties.put("hasNext", Map.of("type", "boolean"));
        properties.put("checkedAt", text(40));
        return object(properties);
    }
}
