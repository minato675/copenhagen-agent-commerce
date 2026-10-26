package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.auth.McpIdentity;
import java.util.Map;

import static com.copenhagen.mcp.cart.CartService.invalid;
import static com.copenhagen.mcp.cart.CartService.lines;
import static com.copenhagen.mcp.cart.CartService.map;
import static com.copenhagen.mcp.cart.CartService.number;

public final class CartEditService {

    private final CoreCartEdits core;
    private final CartService boundary;

    public CartEditService(CoreCartEdits core, CartService boundary) {
        this.core = core;
        this.boundary = boundary;
    }

    public Map<String, Object> update(McpIdentity identity, Map<String, Object> request) {
        return change(identity, request, true);
    }

    public Map<String, Object> delete(McpIdentity identity, Map<String, Object> request) {
        return change(identity, request, false);
    }

    private Map<String, Object> change(McpIdentity identity, Map<String, Object> request, boolean update) {
        boundary.validateInput(request, update ? CartEditSchemas.UPDATE_INPUT : CartEditSchemas.DELETE_INPUT);
        Map<String, Object> result = boundary.checkedMutation(identity, request,
                core.change(identity, update ? "UPDATE" : "DELETE", Map.copyOf(request)),
                update ? CartEditSchemas.CORE_UPDATE : CartEditSchemas.CORE_DELETE);
        if (result.get("status").equals("APPLIED")) {
            if (!request.get("lineId").equals(result.get("affectedLineId"))) {
                throw invalid();
            }
            Map<String, Object> line = lines(map(result.get("cart"))).stream()
                    .filter(item -> item.get("lineId").equals(request.get("lineId"))).findFirst().orElse(null);
            if (update ? line == null || !line.get("optionId").equals(request.get("optionId"))
                    || number(line.get("quantity")) != number(request.get("quantity")) : line != null) {
                throw invalid();
            }
        }
        return result;
    }
}
