package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreApiException;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sequential writes reuse Core's persisted idempotent responses, including on MCP restart. */
final class CartBatchService {
    private final CoreCommerceService service;
    private final JsonSchemaValidator validator;

    CartBatchService(CoreCommerceService service, JsonSchemaValidator validator) {
        this.service = service;
        this.validator = validator;
    }

    Map<String, Object> apply(McpIdentity identity, Map<String, Object> input) {
        identity.requireScopes(Set.of("cart:write"));
        if (input == null || !validator.validate(CommerceSchemas.CART_BATCH, input).valid()) {
            throw CoreCommerceService.failure("INVALID_ARGUMENT", false);
        }
        List<?> changes = (List<?>) input.get("changes");
        Set<Object> ids = new HashSet<>();
        for (Object entry : changes) {
            if (!ids.add(((Map<?, ?>) entry).get("cartItemId"))) {
                throw CoreCommerceService.failure("INVALID_ARGUMENT", false);
            }
        }
        Object version = input.get("cartVersion");
        List<Object> applied = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appliedItemIds", applied);
        result.put("outcome", "APPLIED");
        result.put("nextAction", "DISPLAY_CART_ONCE");
        for (int index = 0; index < changes.size(); index++) {
            Map<?, ?> change = (Map<?, ?>) changes.get(index);
            Map<String, Object> args = new LinkedHashMap<>();
            change.forEach((key, value) -> args.put((String) key, value));
            String operation = (String) args.remove("operation");
            args.put("cartVersion", version);
            args.put("idempotencyKey", UUID.nameUUIDFromBytes(
                    ("cart-batch:" + input.get("idempotencyKey") + ":" + index)
                            .getBytes(StandardCharsets.UTF_8)).toString());
            try {
                Map<?, ?> data = (Map<?, ?>) service.mutate(identity,
                        operation.equals("DELETE") ? "DELETE" : "PATCH", args).get("data");
                applied.add(change.get("cartItemId"));
                if (!(data.get("cart") instanceof Map<?, ?> cart) || !(cart.get("version") instanceof Number)) {
                    result.put("outcome", "PARTIAL");
                    result.put("nextAction", "REFETCH_CART_DO_NOT_REPLAY_APPLIED");
                    break;
                }
                version = cart.get("version");
            } catch (CoreApiException exception) {
                if (Set.of("AUTHENTICATION_REQUIRED", "INVALID_TOKEN", "INSUFFICIENT_SCOPE")
                        .contains(exception.getMessage())) {
                    throw exception;
                }
                result.put("outcome", applied.isEmpty() ? "STOPPED" : "PARTIAL");
                result.put("stoppedIndex", index);
                result.put("error", exception.body().get("error"));
                result.put("nextAction", "CHECK_CART_AND_ERROR_BEFORE_RETRY");
                break;
            }
        }
        result.put("unconfirmedCount", changes.size() - applied.size());
        return Map.of("kind", "cart.batch", "data", result);
    }
}
