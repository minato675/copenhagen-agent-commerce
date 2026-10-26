package com.copenhagen.mcp.handoff;

import com.copenhagen.mcp.auth.McpAuthError;
import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class PurchaseHandoffService {
    private final CorePurchaseHandoff core;
    private final HandoffProperties properties;
    private final JsonSchemaValidator validator;
    private final McpJsonMapper mapper;
    private final Clock clock;

    public PurchaseHandoffService(CorePurchaseHandoff core, HandoffProperties properties,
            JsonSchemaValidator validator, McpJsonMapper mapper, Clock clock) {
        this.core = core;
        this.properties = properties;
        this.validator = validator;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Map<String, Object> prepare(McpIdentity identity, Map<String, Object> request) {
        if (request == null || !validator.validate(HandoffSchemas.INPUT, request).valid()) {
            throw new CoreClientException(ToolErrorCode.INVALID_ARGUMENT);
        }
        Map<String, Object> envelope = core.prepare(identity, Map.copyOf(request));
        try {
            if (envelope == null || mapper.writeValueAsBytes(envelope).length > 16384
                    || !validator.validate(HandoffSchemas.CORE, envelope).valid()) {
                throw invalid();
            }
        } catch (IOException exception) {
            throw invalid();
        }
        identity.requireOwner((String) envelope.get("publicUserRef"));
        if (!identity.connectionRef().equals(envelope.get("connectionRef"))) {
            throw new McpAuthException(McpAuthError.ACCESS_DENIED);
        }
        Map<String, Object> data = new LinkedHashMap<>(map(envelope.get("data")));
        Map<String, Object> echoed = map(data.get("request"));
        if (!request.get("cartId").equals(echoed.get("cartId"))
                || !request.get("idempotencyKey").equals(echoed.get("idempotencyKey"))
                || ((Number) request.get("expectedVersion")).longValue()
                != ((Number) echoed.get("expectedVersion")).longValue()) {
            throw invalid();
        }
        checkStatus(data);
        try {
            Instant checked = Instant.parse((String) data.get("checkedAt"));
            if (data.get("status").equals("READY")) {
                Instant expires = Instant.parse((String) data.get("expiresAt"));
                if (!expires.isAfter(checked) || !data.get("appLink")
                        .equals(properties.linkBase().toASCIIString() + "#" + data.get("purchaseRef"))) {
                    throw invalid();
                }
                if (!expires.isAfter(clock.instant())) {
                    data.put("status", "EXPIRED");
                    data.put("reason", "EXPIRED");
                    data.put("nextAction", "REVIEW_CART");
                    for (String key : Set.of("purchaseRef", "expiresAt", "appLink")) {
                        data.put(key, null);
                    }
                }
            }
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
        return data;
    }

    private static void checkStatus(Map<String, Object> data) {
        String status = (String) data.get("status");
        Set<String> reasons = switch (status) {
            case "READY" -> Set.of("NONE");
            case "REJECTED" -> Set.of("EMPTY_CART", "MULTIPLE_MERCHANTS", "UNAVAILABLE");
            case "CONFLICT" -> Set.of("VERSION_CONFLICT", "KEY_CONFLICT");
            case "UNKNOWN" -> Set.of("OUTCOME_UNKNOWN");
            case "EXPIRED" -> Set.of("EXPIRED");
            default -> throw invalid();
        };
        String action = status.equals("READY") ? "OPEN_APP"
                : status.equals("UNKNOWN") ? "RETRY_SAME_REQUEST" : "REVIEW_CART";
        if (!reasons.contains(data.get("reason")) || !action.equals(data.get("nextAction"))) {
            throw invalid();
        }
        for (String key : Set.of("purchaseRef", "expiresAt", "appLink")) {
            if (status.equals("READY") != (data.get(key) != null)) {
                throw invalid();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static CoreClientException invalid() {
        return new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE);
    }
}
