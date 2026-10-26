package com.copenhagen.mcp.handoff;

import com.copenhagen.mcp.cart.CartSchemas;
import com.copenhagen.mcp.catalog.CatalogSchemas;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.copenhagen.mcp.catalog.CatalogSchemas.enumeration;
import static com.copenhagen.mcp.catalog.CatalogSchemas.identifier;
import static com.copenhagen.mcp.catalog.CatalogSchemas.integer;
import static com.copenhagen.mcp.catalog.CatalogSchemas.nullable;
import static com.copenhagen.mcp.catalog.CatalogSchemas.object;
import static com.copenhagen.mcp.catalog.CatalogSchemas.text;

public final class HandoffSchemas {
    public static final Map<String, Object> INPUT = object(Map.of("cartId", identifier(),
            "expectedVersion", integer(0, CartSchemas.MAX_VERSION), "idempotencyKey",
            Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{16,96}$")));
    public static final Map<String, Object> OUTPUT = output();
    public static final Map<String, Object> CORE = CatalogSchemas.envelope(OUTPUT);

    private HandoffSchemas() {
    }

    private static Map<String, Object> output() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", enumeration("purchase.handoff"));
        fields.put("mode", enumeration("QA", "LIVE"));
        fields.put("request", INPUT);
        fields.put("status", enumeration("READY", "REJECTED", "CONFLICT", "UNKNOWN", "EXPIRED"));
        fields.put("reason", enumeration("NONE", "EMPTY_CART", "MULTIPLE_MERCHANTS", "UNAVAILABLE",
                "VERSION_CONFLICT", "KEY_CONFLICT", "OUTCOME_UNKNOWN", "EXPIRED"));
        fields.put("purchaseRef", nullable(identifier()));
        fields.put("expiresAt", nullable(text(40)));
        fields.put("appLink", nullable(text(2048)));
        fields.put("nextAction", enumeration("OPEN_APP", "REVIEW_CART", "RETRY_SAME_REQUEST"));
        fields.put("checkedAt", text(40));
        return object(fields);
    }
}
