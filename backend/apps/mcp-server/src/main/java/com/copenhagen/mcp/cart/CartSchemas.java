package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.catalog.CatalogSchemas;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.copenhagen.mcp.catalog.CatalogSchemas.array;
import static com.copenhagen.mcp.catalog.CatalogSchemas.enumeration;
import static com.copenhagen.mcp.catalog.CatalogSchemas.identifier;
import static com.copenhagen.mcp.catalog.CatalogSchemas.integer;
import static com.copenhagen.mcp.catalog.CatalogSchemas.nullable;
import static com.copenhagen.mcp.catalog.CatalogSchemas.object;
import static com.copenhagen.mcp.catalog.CatalogSchemas.text;

public final class CartSchemas {

    public static final long MAX_AMOUNT = 9_000_000_000_000L;
    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    public static final Map<String, Object> OPTION = Map.of("type", "string",
            "pattern", "^[A-Za-z0-9:_-]{0,96}$");
    public static final Map<String, Object> ADD_INPUT = addInput();
    public static final Map<String, Object> LINE = line();
    public static final Map<String, Object> CART = object(Map.of("cartId", identifier(),
            "version", integer(0, MAX_VERSION), "lines", array(LINE, 100),
            "approvalState", enumeration("REVALIDATION_REQUIRED")));
    public static final Map<String, Object> ADD_OUTPUT = mutation(ADD_INPUT, "ADD");
    public static final Map<String, Object> CORE_ADD = CatalogSchemas.envelope(ADD_OUTPUT);

    private CartSchemas() {
    }

    static Map<String, Object> mutation(Map<String, Object> input, String action) {
        return object(Map.of("kind", enumeration("cart.mutation"), "mode", enumeration("QA", "LIVE"),
                "action", enumeration(action), "request", input,
                "status", enumeration("APPLIED", "REJECTED", "CONFLICT", "UNKNOWN"),
                "reason", enumeration("NONE", "OPTION_REQUIRED", "INVALID_OPTION", "UNAVAILABLE",
                        "INSUFFICIENT_STOCK", "NOT_FOUND", "VERSION_CONFLICT", "KEY_CONFLICT",
                        "LIMIT_EXCEEDED", "OUTCOME_UNKNOWN", "DUPLICATE_SELECTION"),
                "cart", nullable(CART), "affectedLineId", nullable(identifier()), "checkedAt", text(40)));
    }

    private static Map<String, Object> addInput() {
        return object(Map.of("cartId", identifier(), "expectedVersion", integer(0, MAX_VERSION),
                "idempotencyKey", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{16,96}$"),
                "productId", identifier(), "merchantId", identifier(), "optionId", OPTION,
                "quantity", integer(1, 99)));
    }

    private static Map<String, Object> line() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("lineId", identifier());
        fields.put("productId", identifier());
        fields.put("merchantId", identifier());
        fields.put("merchantName", text(80));
        fields.put("name", text(240));
        fields.put("imageUrl", text(2048));
        fields.put("optionId", OPTION);
        fields.put("optionLabel", text(240));
        fields.put("quantity", integer(1, 99));
        fields.put("unitPrice", nullable(integer(0, CatalogSchemas.MAX_PRICE)));
        fields.put("lineTotal", nullable(integer(0, MAX_AMOUNT)));
        fields.put("currency", Map.of("type", "string", "pattern", "^[A-Z]{3}$"));
        fields.put("priceStatus", enumeration("ESTIMATED", "CONFIRMED", "UNKNOWN"));
        return object(fields);
    }
}
