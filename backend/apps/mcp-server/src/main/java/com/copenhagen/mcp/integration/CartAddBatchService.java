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

/** One host call; sequential Core writes retain persisted idempotency and optimistic versions. */
final class CartAddBatchService {
    private final CoreCommerceService service;
    private final JsonSchemaValidator validator;

    CartAddBatchService(CoreCommerceService service, JsonSchemaValidator validator) {
        this.service = service;
        this.validator = validator;
    }

    Map<String, Object> add(McpIdentity identity, Map<String, Object> input) {
        identity.requireScopes(Set.of("cart:write"));
        if (input == null || !validator.validate(CommerceSchemas.ADD_BATCH, input).valid()) {
            throw CoreCommerceService.failure("INVALID_ARGUMENT", false);
        }
        List<?> items = (List<?>) input.get("items");
        Set<List<Object>> selections = new HashSet<>();
        for (Object item : items) {
            Map<?, ?> row = (Map<?, ?>) item;
            if (!selections.add(List.of(row.get("merchantId"), row.get("merchantProductId"),
                    row.get("merchantVariantId")))) {
                throw CoreCommerceService.failure("INVALID_ARGUMENT", false);
            }
        }
        Object version = input.get("cartVersion");
        List<Integer> applied = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "APPLIED");
        result.put("nextAction", "DISPLAY_CART_ONCE");
        result.put("appliedIndexes", applied);
        result.put("lastConfirmedCartVersion", version);
        // Prefix and position stay stable across retries/restarts. Never derive keys from mutable request data.
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> args = new LinkedHashMap<>();
            ((Map<?, ?>) items.get(index)).forEach((key, value) -> args.put((String) key, value));
            args.put("cartVersion", version);
            args.put("idempotencyKey", UUID.nameUUIDFromBytes(
                    ("cart-add-batch:" + input.get("idempotencyKey") + ":" + index)
                            .getBytes(StandardCharsets.UTF_8)).toString());
            try {
                Map<?, ?> data = (Map<?, ?>) service.mutate(identity, "POST", args).get("data");
                applied.add(index);
                if (!(data.get("cart") instanceof Map<?, ?> cart) || !(cart.get("version") instanceof Number)) {
                    result.put("outcome", "PARTIAL");
                    result.put("nextAction", "REFETCH_CART_DO_NOT_REPLAY_APPLIED");
                    result.put("cartVersionKnown", false);
                    break;
                }
                version = cart.get("version");
                result.put("lastConfirmedCartVersion", version);
            } catch (CoreApiException exception) {
                if (Set.of("AUTHENTICATION_REQUIRED", "INVALID_TOKEN", "INSUFFICIENT_SCOPE", "ACCESS_DENIED")
                        .contains(exception.getMessage())) {
                    // Host must handle OAuth. Replay the original batch after reconnect, never a new key.
                    throw exception;
                }
                Map<?, ?> error = (Map<?, ?>) exception.body().get("error");
                result.put("outcome", applied.isEmpty() ? "STOPPED" : "PARTIAL");
                result.put("stoppedIndex", index);
                result.put("error", error);
                result.put("cartVersionKnown", false);
                result.put("nextAction", "UNKNOWN".equals(error.get("outcome"))
                        ? "RETRY_ORIGINAL_BATCH_SAME_KEY_AND_BODY" : "CHECK_CART_AND_ERROR_BEFORE_RETRY");
                break;
            }
        }
        result.putIfAbsent("cartVersionKnown", true);
        result.put("unconfirmedIndexes", java.util.stream.IntStream.range(applied.size(), items.size())
                .boxed().toList());
        result.put("unconfirmedCount", items.size() - applied.size());
        result.put("snapshotNotice", "Versions may be persisted replay snapshots; display_cart reads current state.");
        return Map.of("kind", "cart.add.batch", "data", result);
    }
}
