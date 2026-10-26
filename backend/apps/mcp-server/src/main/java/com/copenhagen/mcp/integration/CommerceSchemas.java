package com.copenhagen.mcp.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core 최종 계약의 필드만 허용하는 MCP 입력. 사용자 신원과 금액은 입력받지 않는다. */
public final class CommerceSchemas {
    public static final Map<String, Object> ID = Map.of("type", "string", "pattern", "^[A-Za-z0-9_:-]{1,96}$");
    public static final Map<String, Object> VERSION = Map.of("type", "integer", "minimum", 0);
    public static final Map<String, Object> QUANTITY = Map.of("type", "integer", "minimum", 1, "maximum", 99);
    public static final Map<String, Object> KEY = Map.of("type", "string", "pattern",
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    public static final Map<String, Object> EMPTY = object(Map.of(), List.of());
    public static final Map<String, Object> SEARCH = search();
    public static final Map<String, Object> DETAIL = object(Map.of("merchantId", ID, "merchantProductId", ID),
            List.of("merchantId", "merchantProductId"));
    public static final Map<String, Object> ADD = object(Map.of("cartVersion", VERSION, "merchantId", ID,
            "merchantProductId", ID, "merchantVariantId", ID, "quantity", QUANTITY, "idempotencyKey", KEY,
            "selectionBasis", Map.of("type", "string", "enum", List.of("USER_SELECTED", "USER_DELEGATED"),
                    "description", "사용자가 옵션·수량을 선택했으면 USER_SELECTED, 선택을 명시적으로 위임했으면 USER_DELEGATED")),
            List.of("cartVersion", "merchantId", "merchantProductId", "merchantVariantId",
                    "quantity", "idempotencyKey", "selectionBasis"));
    public static final Map<String, Object> ADD_BATCH = object(Map.of("cartVersion", VERSION,
            "idempotencyKey", KEY, "items", Map.of("type", "array", "minItems", 1, "maxItems", 8,
                    "items", object(Map.of("merchantId", ID, "merchantProductId", ID, "merchantVariantId", ID,
                            "quantity", QUANTITY, "selectionBasis", Map.of("type", "string", "enum",
                                    List.of("USER_SELECTED", "USER_DELEGATED"))),
                            List.of("merchantId", "merchantProductId", "merchantVariantId", "quantity",
                                    "selectionBasis")))), List.of("cartVersion", "idempotencyKey", "items"));
    public static final Map<String, Object> UPDATE = object(Map.of("cartVersion", VERSION, "cartItemId", ID,
            "merchantVariantId", ID, "quantity", QUANTITY, "idempotencyKey", KEY),
            List.of("cartVersion", "cartItemId", "merchantVariantId", "quantity", "idempotencyKey"));
    public static final Map<String, Object> DELETE = object(Map.of("cartVersion", VERSION, "cartItemId", ID,
            "idempotencyKey", KEY), List.of("cartVersion", "cartItemId", "idempotencyKey"));
    public static final Map<String, Object> CART_BATCH = object(Map.of("cartVersion", VERSION,
            "idempotencyKey", KEY, "changes", Map.of("type", "array", "minItems", 1, "maxItems", 30,
                    "items", Map.of("oneOf", List.of(
                            object(Map.of("operation", Map.of("const", "UPDATE"), "cartItemId", ID,
                                    "merchantVariantId", ID, "quantity", QUANTITY),
                                    List.of("operation", "cartItemId", "merchantVariantId", "quantity")),
                            object(Map.of("operation", Map.of("const", "DELETE"), "cartItemId", ID),
                                    List.of("operation", "cartItemId")))))),
            List.of("cartVersion", "idempotencyKey", "changes"));
    public static final Map<String, Object> PURCHASE = object(Map.of("cartId", ID, "cartVersion", VERSION,
            "idempotencyKey", KEY), List.of("cartId", "cartVersion", "idempotencyKey"));
    public static final Map<String, Object> OUTPUT = object(Map.of("kind", Map.of("type", "string"),
            "data", Map.of("type", "object")), List.of("kind", "data"));
    public static final Map<String, Object> PURCHASE_STATUS = object(
            Map.of("purchaseRequestId", KEY), List.of("purchaseRequestId"));
    public static final Map<String, Object> PURCHASE_HISTORY = object(Map.of(
            "pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 50, "default", 20),
            "cursor", Map.of("type", "string", "maxLength", 4096)), List.of());
    public static final Map<String, Object> PURCHASE_NOTIFY = object(
            Map.of("purchaseRequestId", KEY, "idempotencyKey", KEY),
            List.of("purchaseRequestId", "idempotencyKey"));

    private CommerceSchemas() {
    }

    private static Map<String, Object> search() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "maxLength", 120));
        properties.put("relatedQueries", Map.of("type", "array", "maxItems", 2, "uniqueItems", true,
                "items", Map.of("type", "string", "minLength", 1, "maxLength", 120),
                "description", "대화에서 확인한 유사 객체 검색어. 직접 조건은 query에 유지. 예: 머그컵, 텀블러"));
        properties.put("categoryId", ID);
        properties.put("merchantId", ID);
        properties.put("brand", Map.of("type", "string", "maxLength", 120));
        properties.put("minPrice", Map.of("type", "integer", "minimum", 0, "maximum", 1000000000000L));
        properties.put("maxPrice", Map.of("type", "integer", "minimum", 0, "maximum", 1000000000000L));
        properties.put("sort", Map.of("type", "string", "enum", List.of("RELEVANCE", "PRICE_ASC", "PRICE_DESC")));
        properties.put("cursor", Map.of("type", "string", "maxLength", 4096));
        properties.put("pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 30));
        return object(properties, List.of());
    }

    static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
}
