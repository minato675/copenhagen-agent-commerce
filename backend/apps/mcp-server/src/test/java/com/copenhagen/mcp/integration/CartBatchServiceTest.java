package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreApiException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CartBatchServiceTest {
    private final CoreCommerceService core = mock(CoreCommerceService.class);
    private final CartBatchService batch = new CartBatchService(core, McpJsonDefaults.getSchemaValidator());
    private final McpIdentity identity = new McpIdentity("user", "connection", Set.of("cart:write"),
            Instant.now().plusSeconds(300));
    private final Map<String, Object> update = Map.of("operation", "UPDATE", "cartItemId", "a",
            "merchantVariantId", "v1", "quantity", 3);
    private final Map<String, Object> delete = Map.of("operation", "DELETE", "cartItemId", "b");

    private Map<String, Object> input(List<?> changes) {
        return Map.of("cartVersion", 7, "changes", changes,
                "idempotencyKey", "00000000-0000-4000-8000-000000000001");
    }

    @Test
    void sequentialVersionsAndKeysRemainStableAcrossRetryAndRestart() {
        List<Map<String, Object>> calls = new ArrayList<>();
        when(core.mutate(any(), any(), any())).thenAnswer(call -> {
            Map<String, Object> args = call.getArgument(2);
            calls.add(args);
            return Map.of("data", Map.of("outcome", "APPLIED", "cart",
                    Map.of("version", args.get("cartItemId").equals("a") ? 11 : 12)));
        });
        batch.apply(identity, input(List.of(update, delete)));
        new CartBatchService(core, McpJsonDefaults.getSchemaValidator())
                .apply(identity, input(List.of(update, delete)));
        assertEquals(7, calls.get(0).get("cartVersion"));
        assertEquals(11, calls.get(1).get("cartVersion"));
        assertEquals(calls.subList(0, 2), calls.subList(2, 4));
        assertNotEquals(calls.get(0).get("idempotencyKey"), calls.get(1).get("idempotencyKey"));
    }

    @Test
    void failureStopsRemainingWritesAndReportsConfirmedSuccessSeparately() {
        List<Map<String, Object>> calls = new ArrayList<>();
        when(core.mutate(any(), any(), any())).thenAnswer(call -> {
            calls.add(call.getArgument(2));
            if (calls.size() == 2) {
                throw new CoreApiException("CART_VERSION_CONFLICT", "NOT_APPLIED", "test");
            }
            return Map.of("data", Map.of("outcome", "APPLIED", "cart", Map.of("version", 8)));
        });
        Map<?, ?> data = (Map<?, ?>) batch.apply(identity, input(List.of(update, delete,
                Map.of("operation", "DELETE", "cartItemId", "c")))).get("data");
        assertEquals(2, calls.size());
        assertEquals("PARTIAL", data.get("outcome"));
        assertEquals(List.of("a"), data.get("appliedItemIds"));
        assertEquals(2, data.get("unconfirmedCount"));
    }

    @Test
    void missingMutationSnapshotDoesNotGuessNextVersionOrRepeatAppliedItem() {
        when(core.mutate(any(), any(), any())).thenReturn(Map.of("data", Map.of("outcome", "APPLIED")));
        Map<?, ?> data = (Map<?, ?>) batch.apply(identity, input(List.of(update, delete))).get("data");
        assertEquals(List.of("a"), data.get("appliedItemIds"));
        assertEquals("REFETCH_CART_DO_NOT_REPLAY_APPLIED", data.get("nextAction"));
        assertEquals(1, data.get("unconfirmedCount"));
    }

    @Test
    void duplicateOrMalformedChangesAreRejectedBeforeAnyWrite() {
        for (List<?> changes : List.of(List.of(), List.of(update, update),
                List.of(update, Map.of("operation", "UPDATE", "cartItemId", "b", "quantity", 0)))) {
            assertThrows(CoreApiException.class, () -> batch.apply(identity, input(changes)));
        }
        verifyNoInteractions(core);
    }

    @Test
    void unknownOutcomeIsPreservedAndAuthenticationIsNeverDisguisedAsSuccess() {
        when(core.mutate(any(), any(), any())).thenThrow(new CoreApiException("CORE_UNAVAILABLE", "UNKNOWN", "test"));
        Map<?, ?> data = (Map<?, ?>) batch.apply(identity, input(List.of(update))).get("data");
        assertEquals("UNKNOWN", ((Map<?, ?>) data.get("error")).get("outcome"));
        assertEquals(List.of(), data.get("appliedItemIds"));
        org.mockito.Mockito.reset(core);
        when(core.mutate(any(), any(), any())).thenThrow(new CoreApiException("INVALID_TOKEN", "NOT_APPLIED", "test"));
        assertThrows(CoreApiException.class, () -> batch.apply(identity, input(List.of(update))));
    }
}
