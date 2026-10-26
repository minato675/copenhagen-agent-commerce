package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.auth.McpAuthError;
import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Core가 확인한 스냅샷만 전달한다. 원장·멱등성 기록·가격 계산은 Core 책임이다. */
public final class CartService {

    private final CoreCart core;
    private final JsonSchemaValidator validator;
    private final McpJsonMapper mapper;

    public CartService(CoreCart core, JsonSchemaValidator validator, McpJsonMapper mapper) {
        this.core = core;
        this.validator = validator;
        this.mapper = mapper;
    }

    public Map<String, Object> add(McpIdentity identity, Map<String, Object> request) {
        validateInput(request, CartSchemas.ADD_INPUT);
        Map<String, Object> data = checkedMutation(identity, request,
                core.add(identity, Map.copyOf(request)), CartSchemas.CORE_ADD);
        if (data.get("status").equals("APPLIED")) {
            Map<String, Object> line = lines(map(data.get("cart"))).stream()
                    .filter(value -> value.get("lineId").equals(data.get("affectedLineId")))
                    .findFirst().orElseThrow(CartService::invalid);
            if (!line.get("productId").equals(request.get("productId"))
                    || !line.get("merchantId").equals(request.get("merchantId"))
                    || !line.get("optionId").equals(request.get("optionId"))
                    || number(line.get("quantity")) < number(request.get("quantity"))) {
                throw invalid();
            }
        }
        return data;
    }

    Map<String, Object> checkedMutation(McpIdentity identity, Map<String, Object> request,
            Map<String, Object> envelope, Map<String, Object> schema) {
        Map<String, Object> data = checked(identity, envelope, schema);
        if (!equivalent(request, data.get("request"))) {
            throw invalid();
        }
        String status = (String) data.get("status");
        String reason = (String) data.get("reason");
        if (status.equals("APPLIED") != reason.equals("NONE")
                || status.equals("UNKNOWN") != reason.equals("OUTCOME_UNKNOWN")
                || status.equals("CONFLICT") != Set.of("KEY_CONFLICT", "VERSION_CONFLICT").contains(reason)) {
            throw invalid();
        }
        if (data.get("cart") == null) {
            if (!status.equals("UNKNOWN") || data.get("affectedLineId") != null) {
                throw invalid();
            }
            return data;
        }
        Map<String, Object> cart = map(data.get("cart"));
        validateCart(cart);
        if (!request.get("cartId").equals(cart.get("cartId"))) {
            throw invalid();
        }
        if (status.equals("APPLIED")) {
            if (number(cart.get("version")) != number(request.get("expectedVersion")) + 1
                    || data.get("affectedLineId") == null) {
                throw invalid();
            }
        } else if (data.get("affectedLineId") != null) {
            throw invalid();
        }
        return data;
    }

    void validateInput(Map<String, Object> input, Map<String, Object> schema) {
        if (input == null || !validator.validate(schema, input).valid()) {
            throw new CoreClientException(ToolErrorCode.INVALID_ARGUMENT);
        }
    }

    Map<String, Object> checked(McpIdentity identity, Map<String, Object> envelope, Map<String, Object> schema) {
        try {
            if (envelope == null || mapper.writeValueAsBytes(envelope).length > 262144
                    || !validator.validate(schema, envelope).valid()) {
                throw invalid();
            }
        } catch (IOException exception) {
            throw invalid();
        }
        identity.requireOwner((String) envelope.get("publicUserRef"));
        if (!identity.connectionRef().equals(envelope.get("connectionRef"))) {
            throw new McpAuthException(McpAuthError.ACCESS_DENIED);
        }
        return map(envelope.get("data"));
    }

    static void validateCart(Map<String, Object> cart) {
        Set<Object> ids = new HashSet<>();
        Set<List<Object>> selections = new HashSet<>();
        for (Map<String, Object> line : lines(cart)) {
            Object price = line.get("unitPrice");
            Object total = line.get("lineTotal");
            if (!ids.add(line.get("lineId")) || !selections.add(List.of(line.get("merchantId"),
                    line.get("productId"), line.get("optionId"))) || ((String) line.get("name")).isBlank()
                    || ((String) line.get("merchantName")).isBlank()
                    || (price == null) != line.get("priceStatus").equals("UNKNOWN")
                    || (price == null) != (total == null)
                    || (price != null && number(total) != number(price) * number(line.get("quantity")))) {
                throw invalid();
            }
            String image = (String) line.get("imageUrl");
            if (!image.isEmpty()) {
                try {
                    URI uri = URI.create(image);
                    if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                        throw invalid();
                    }
                } catch (IllegalArgumentException exception) {
                    throw invalid();
                }
            }
        }
    }

    static boolean equivalent(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return a.longValue() == b.longValue();
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            return a.keySet().equals(b.keySet()) && a.entrySet().stream()
                    .allMatch(entry -> equivalent(entry.getValue(), b.get(entry.getKey())));
        }
        return Objects.equals(left, right);
    }

    static long number(Object value) {
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> lines(Map<String, Object> cart) {
        return (List<Map<String, Object>>) cart.get("lines");
    }

    static CoreClientException invalid() {
        return new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE);
    }
}
