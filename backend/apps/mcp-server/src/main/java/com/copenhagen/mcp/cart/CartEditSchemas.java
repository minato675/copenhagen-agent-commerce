package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.catalog.CatalogSchemas;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.copenhagen.mcp.catalog.CatalogSchemas.identifier;
import static com.copenhagen.mcp.catalog.CatalogSchemas.integer;
import static com.copenhagen.mcp.catalog.CatalogSchemas.object;

public final class CartEditSchemas {

    public static final Map<String, Object> DELETE_INPUT = input(false);
    public static final Map<String, Object> UPDATE_INPUT = input(true);
    public static final Map<String, Object> DELETE_OUTPUT = CartSchemas.mutation(DELETE_INPUT, "DELETE");
    public static final Map<String, Object> UPDATE_OUTPUT = CartSchemas.mutation(UPDATE_INPUT, "UPDATE");
    public static final Map<String, Object> CORE_DELETE = CatalogSchemas.envelope(DELETE_OUTPUT);
    public static final Map<String, Object> CORE_UPDATE = CatalogSchemas.envelope(UPDATE_OUTPUT);

    private CartEditSchemas() {
    }

    private static Map<String, Object> input(boolean update) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("cartId", identifier());
        fields.put("lineId", identifier());
        fields.put("expectedVersion", integer(0, CartSchemas.MAX_VERSION));
        fields.put("idempotencyKey", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{16,96}$"));
        if (update) {
            fields.put("optionId", CartSchemas.OPTION);
            fields.put("quantity", integer(1, 99));
        }
        return object(fields);
    }
}
