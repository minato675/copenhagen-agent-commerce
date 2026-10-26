package com.copenhagen.mcp.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.copenhagen.mcp.catalog.CatalogSchemas.MAX_PRICE;
import static com.copenhagen.mcp.catalog.CatalogSchemas.array;
import static com.copenhagen.mcp.catalog.CatalogSchemas.enumeration;
import static com.copenhagen.mcp.catalog.CatalogSchemas.identifier;
import static com.copenhagen.mcp.catalog.CatalogSchemas.integer;
import static com.copenhagen.mcp.catalog.CatalogSchemas.nullable;
import static com.copenhagen.mcp.catalog.CatalogSchemas.object;
import static com.copenhagen.mcp.catalog.CatalogSchemas.text;

public final class DetailSchemas {

    public static final Map<String, Object> INPUT = Map.of("type", "object", "properties",
            Map.of("productId", identifier(), "merchantId", identifier(), "optionId", identifier()),
            "required", List.of("productId", "merchantId"), "additionalProperties", false);
    public static final Map<String, Object> VARIANT = object(Map.of(
            "optionId", identifier(), "label", text(240), "price", nullable(integer(0, MAX_PRICE)),
            "availability", enumeration("IN_STOCK", "OUT_OF_STOCK", "DISCONTINUED", "UNKNOWN"),
            "purchaseAllowed", nullable(Map.of("type", "boolean"))));
    public static final Map<String, Object> DETAIL = detail();
    public static final Map<String, Object> OUTPUT = object(Map.of(
            "kind", enumeration("catalog.detail"), "mode", enumeration("QA", "LIVE"),
            "status", enumeration("FOUND", "NOT_FOUND"), "request", INPUT,
            "product", nullable(DETAIL), "checkedAt", text(40)));
    public static final Map<String, Object> CORE = CatalogSchemas.envelope(OUTPUT);

    private DetailSchemas() {
    }

    private static Map<String, Object> detail() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", identifier());
        result.put("name", text(240));
        result.put("brand", text(120));
        result.put("category", text(160));
        result.put("imageUrl", text(2048));
        result.put("description", text(4000));
        result.put("specs", array(object(Map.of("name", text(80), "value", text(400))), 30));
        result.put("offer", CatalogSchemas.OFFER);
        result.put("variants", array(VARIANT, 50));
        result.put("optionsComplete", Map.of("type", "boolean"));
        result.put("optionsRequired", nullable(Map.of("type", "boolean")));
        result.put("selectionStatus", enumeration("NONE", "REQUIRED", "VALID", "INVALID", "UNKNOWN"));
        result.put("selectedOptionId", nullable(identifier()));
        result.put("stockQuantity", nullable(integer(0, 1_000_000_000)));
        result.put("shipping", object(Map.of("status", enumeration("KNOWN", "UNKNOWN"),
                "fee", nullable(integer(0, MAX_PRICE)), "description", text(400))));
        return object(result);
    }
}
