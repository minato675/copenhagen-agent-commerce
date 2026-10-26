package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.tool.ToolErrorCode;
import com.copenhagen.mcp.tool.WidgetActionTrace;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Short-lived references never authorize execution; the original tool still checks permissions. */
final class WidgetActionStore {
    private static final Map<String, Map<String, Object>> SCHEMAS = Map.of(
            "search_products", CommerceSchemas.SEARCH, "get_product_detail", CommerceSchemas.DETAIL,
            "apply_cart_changes", CommerceSchemas.CART_BATCH,
            "get_cart", CommerceSchemas.EMPTY, "add_cart_item", CommerceSchemas.ADD,
            "update_cart_item", CommerceSchemas.UPDATE, "delete_cart_item", CommerceSchemas.DELETE,
            "prepare_purchase_handoff", CommerceSchemas.PURCHASE,
            "get_purchase_status", CommerceSchemas.PURCHASE_STATUS,
            "resend_purchase_notification", CommerceSchemas.PURCHASE_NOTIFY);
    private final Map<String, Entry> entries = new HashMap<>();
    private final McpJsonMapper mapper;
    private final JsonSchemaValidator validator;
    private final Clock clock;

    WidgetActionStore(McpJsonMapper mapper, JsonSchemaValidator validator, Clock clock) {
        this.mapper = mapper;
        this.validator = validator;
        this.clock = clock;
    }

    synchronized Map<String, Object> prepare(McpIdentity identity, Map<String, Object> input) {
        String tool = (String) input.get("tool");
        Object arguments = input.get("arguments");
        if (!SCHEMAS.containsKey(tool)) {
            WidgetActionTrace.record("UNSUPPORTED_TOOL", null, identity, "-", -1);
            throw new CoreClientException(ToolErrorCode.INVALID_ARGUMENT);
        }
        if (!validator.validate(SCHEMAS.get(tool), arguments).valid()) {
            WidgetActionTrace.record("ARGUMENT_SCHEMA_REJECTED", null, identity, tool, -1);
            throw new CoreClientException(ToolErrorCode.INVALID_ARGUMENT);
        }
        requireScopes(identity, tool, null, -1);
        Instant now = clock.instant();
        entries.entrySet().removeIf(item -> {
            Entry entry = item.getValue();
            if (entry.expiresAt().isAfter(now)) {
                return false;
            }
            WidgetActionTrace.record("EXPIRED_REMOVED", item.getKey(),
                    new McpIdentity(entry.owner(), entry.connection(), Set.of(), entry.expiresAt()),
                    entry.tool(), Duration.between(entry.createdAt(), now).toMillis());
            return true;
        });
        // Fail closed instead of evicting another still-valid selection.
        if (entries.size() >= 10000 || entries.values().stream()
                .filter(entry -> entry.owner().equals(identity.publicUserRef())).count() >= 100) {
            WidgetActionTrace.record("CAPACITY_LIMIT", null, identity, tool, -1);
            throw new CoreClientException(ToolErrorCode.ACTION_REFERENCE_UNAVAILABLE);
        }
        try {
            String reference = UUID.randomUUID().toString();
            entries.put(reference, new Entry(identity.publicUserRef(), identity.connectionRef(), tool,
                    mapper.writeValueAsString(arguments), (String) input.get("intent"),
                    now, now.plus(Duration.ofMinutes(15))));
            WidgetActionTrace.record("PREPARED", reference, identity, tool, 0);
            return Map.of("kind", "action.reference", "data", Map.of("reference", reference));
        } catch (IOException exception) {
            WidgetActionTrace.record("SERIALIZATION_ERROR", null, identity, "-", -1);
            throw new CoreClientException(ToolErrorCode.INTERNAL_ERROR);
        }
    }

    synchronized Map<String, Object> resolve(McpIdentity identity, String reference) {
        Entry entry = entries.get(reference);
        Instant now = clock.instant();
        long ageMs = entry == null ? -1 : Duration.between(entry.createdAt(), now).toMillis();
        String reason = entry == null ? "REFERENCE_NOT_FOUND"
                : !entry.expiresAt().isAfter(now) ? "REFERENCE_EXPIRED"
                : !entry.owner().equals(identity.publicUserRef()) ? "OWNER_MISMATCH"
                : !entry.connection().equals(identity.connectionRef()) ? "CONNECTION_MISMATCH" : null;
        if (reason != null) {
            WidgetActionTrace.record(reason, reference, identity, entry == null ? "-" : entry.tool(), ageMs);
            throw new CoreClientException("REFERENCE_EXPIRED".equals(reason)
                    ? ToolErrorCode.ACTION_REFERENCE_EXPIRED : ToolErrorCode.ACTION_REFERENCE_UNAVAILABLE);
        }
        requireScopes(identity, entry.tool(), reference, ageMs);
        try {
            Object arguments = mapper.readValue(entry.arguments(), Object.class);
            WidgetActionTrace.record("RESOLVED", reference, identity, entry.tool(), ageMs);
            return Map.of("kind", "action.resolved", "data", Map.of("tool", entry.tool(),
                    "arguments", arguments));
        } catch (IOException exception) {
            WidgetActionTrace.record("SERIALIZATION_ERROR", null, identity, "-", -1);
            throw new CoreClientException(ToolErrorCode.INTERNAL_ERROR);
        }
    }

    synchronized Map<String, Object> resolveIntent(McpIdentity identity, String intent) {
        // Never choose the latest request: an older card can describe a different mutation.
        var matches = entries.entrySet().stream().filter(item ->
                item.getValue().owner().equals(identity.publicUserRef())
                && item.getValue().connection().equals(identity.connectionRef())
                && intent != null && intent.equals(item.getValue().intent())).toList();
        if (matches.size() != 1) {
            WidgetActionTrace.record(matches.isEmpty() ? "INTENT_NOT_FOUND" : "INTENT_AMBIGUOUS",
                    null, identity, "-", -1);
            throw new CoreClientException(matches.isEmpty() ? ToolErrorCode.ACTION_REFERENCE_UNAVAILABLE
                    : ToolErrorCode.ACTION_REFERENCE_AMBIGUOUS);
        }
        return resolve(identity, matches.getFirst().getKey());
    }

    private static void requireScopes(McpIdentity identity, String tool, String reference, long ageMs) {
        try {
            identity.requireScopes(scopes(tool));
        } catch (McpAuthException exception) {
            WidgetActionTrace.record("INSUFFICIENT_SCOPE", reference, identity, tool, ageMs);
            throw exception;
        }
    }

    private static Set<String> scopes(String tool) {
        return Set.of(switch (tool) {
            case "get_cart" -> "cart:read";
            case "add_cart_item", "update_cart_item", "delete_cart_item", "apply_cart_changes" -> "cart:write";
            case "prepare_purchase_handoff", "get_purchase_status", "resend_purchase_notification"
                    -> "purchase:handoff";
            default -> "catalog:read";
        });
    }

    private record Entry(String owner, String connection, String tool, String arguments, String intent,
            Instant createdAt, Instant expiresAt) {
    }
}
