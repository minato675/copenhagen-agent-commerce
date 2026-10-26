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

public final class CartViewSchemas {

    public static final Map<String, Object> INPUT = object(Map.of());
    public static final Map<String, Object> OUTPUT = object(Map.of("kind", enumeration("cart.view"),
            "mode", enumeration("QA", "LIVE"), "cart", CartSchemas.CART,
            "status", enumeration("EMPTY", "ESTIMATED", "CONFIRMED", "INCOMPLETE", "PARTIAL", "UNAVAILABLE"),
            "groups", array(quote(true), 100), "totals", array(quote(false), 100), "checkedAt", text(40)));
    public static final Map<String, Object> CORE = CatalogSchemas.envelope(OUTPUT);

    private CartViewSchemas() {
    }

    private static Map<String, Object> quote(boolean merchant) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (merchant) {
            fields.put("merchantId", identifier());
        }
        fields.put("currency", Map.of("type", "string", "pattern", "^[A-Z]{3}$"));
        fields.put("status", enumeration("ESTIMATED", "CONFIRMED", "UNKNOWN", "FAILED"));
        for (String name : new String[] {"itemSubtotal", "discount", "shipping", "total"}) {
            fields.put(name, nullable(integer(0, CartSchemas.MAX_AMOUNT)));
        }
        return object(fields);
    }
}
