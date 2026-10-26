package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreApiException;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded read batches reduce host round trips; rendering never repeats the catalog query. */
final class ShoppingBatchService {
    static final Map<String, Object> SEARCH = batch("queries", CommerceSchemas.SEARCH);
    static final Map<String, Object> DETAILS = batch("products", CommerceSchemas.DETAIL);
    static final Map<String, Object> DISPLAY = CommerceSchemas.object(
            Map.of("searchReference", CommerceSchemas.KEY), List.of("searchReference"));
    private final CoreCommerceService commerce;
    private final JsonSchemaValidator validator;
    private final McpJsonMapper mapper;
    private final Clock clock;
    private final Map<String, Snapshot> snapshots = new HashMap<>();

    ShoppingBatchService(CoreCommerceService commerce, JsonSchemaValidator validator,
            McpJsonMapper mapper, Clock clock) {
        this.commerce = commerce;
        this.validator = validator;
        this.mapper = mapper;
        this.clock = clock;
    }

    Map<String, Object> search(McpIdentity identity, Map<String, Object> input) {
        validate(identity, SEARCH, input);
        List<Map<String, Object>> groups = read(identity, input, "queries", true);
        Map<String, Object> data = Map.of("products", List.of(), "searchGroups", groups);
        String reference = save(identity, data);
        return Map.of("kind", "catalog.batch", "data", Map.of("searchReference", reference, "groups", groups));
    }

    Map<String, Object> details(McpIdentity identity, Map<String, Object> input) {
        validate(identity, DETAILS, input);
        return Map.of("kind", "catalog.details", "data", Map.of("results", read(identity, input, "products", false)));
    }

    synchronized Map<String, Object> display(McpIdentity identity, Map<String, Object> input) {
        validate(identity, DISPLAY, input);
        Snapshot snapshot = snapshots.get(input.get("searchReference"));
        if (snapshot == null || !snapshot.expiresAt().isAfter(clock.instant())
                || !snapshot.owner().equals(identity.publicUserRef())
                || !snapshot.connection().equals(identity.connectionRef())) {
            throw CoreCommerceService.failure("ACTION_REFERENCE_UNAVAILABLE", false);
        }
        try {
            return Map.of("kind", "catalog.search", "data",
                    mapper.readValue(snapshot.json(), new TypeRef<Map<String, Object>>() { }));
        } catch (IOException exception) {
            throw CoreCommerceService.failure("CORE_INVALID_RESPONSE", false);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> read(McpIdentity identity, Map<String, Object> input,
            String field, boolean search) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object value : (List<?>) input.get(field)) {
            Map<String, Object> arguments = (Map<String, Object>) value;
            try {
                Map<String, Object> result = search ? commerce.search(identity, arguments)
                        : commerce.detail(identity, arguments);
                results.add(Map.of("request", arguments, "data", result.get("data")));
            } catch (CoreApiException exception) {
                if (Set.of("AUTHENTICATION_REQUIRED", "ACCESS_DENIED", "INSUFFICIENT_SCOPE", "INVALID_TOKEN")
                        .contains(exception.getMessage())) {
                    throw exception;
                }
                results.add(Map.of("request", arguments, "error", exception.getMessage()));
            }
        }
        return results;
    }

    private synchronized String save(McpIdentity identity, Map<String, Object> data) {
        Instant now = clock.instant();
        snapshots.values().removeIf(snapshot -> !snapshot.expiresAt().isAfter(now));
        if (snapshots.size() >= 500 || snapshots.values().stream()
                .filter(snapshot -> snapshot.owner().equals(identity.publicUserRef())).count() >= 10) {
            throw CoreCommerceService.failure("ACTION_REFERENCE_UNAVAILABLE", false);
        }
        try {
            String reference = UUID.randomUUID().toString();
            snapshots.put(reference, new Snapshot(identity.publicUserRef(), identity.connectionRef(),
                    now.plusSeconds(300), mapper.writeValueAsString(data)));
            return reference;
        } catch (IOException exception) {
            throw CoreCommerceService.failure("CORE_INVALID_RESPONSE", false);
        }
    }

    private void validate(McpIdentity identity, Map<String, Object> schema, Map<String, Object> input) {
        identity.requireScopes(Set.of("catalog:read"));
        if (input == null || !validator.validate(schema, input).valid()) {
            throw CoreCommerceService.failure("INVALID_ARGUMENT", false);
        }
    }

    private static Map<String, Object> batch(String field, Map<String, Object> schema) {
        return CommerceSchemas.object(Map.of(field, Map.of("type", "array", "minItems", 1,
                "maxItems", 8, "uniqueItems", true, "items", schema)), List.of(field));
    }

    private record Snapshot(String owner, String connection, Instant expiresAt, String json) { }
}
