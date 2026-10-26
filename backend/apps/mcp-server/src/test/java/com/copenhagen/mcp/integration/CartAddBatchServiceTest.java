package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreApiException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CartAddBatchServiceTest {
    private final CoreCommerceService core = mock(CoreCommerceService.class);
    private final McpIdentity identity = new McpIdentity("user", "connection", Set.of("cart:write"),
            Instant.now().plusSeconds(300));

    private CartAddBatchService service() {
        return new CartAddBatchService(core, McpJsonDefaults.getSchemaValidator());
    }

    private Map<String, Object> item(String product) {
        return Map.of("merchantId", "merchant-a", "merchantProductId", product, "merchantVariantId", "default",
                "quantity", 1, "selectionBasis", "USER_SELECTED");
    }

    private Map<String, Object> input(List<?> items) {
        return Map.of("cartVersion", 7, "items", items,
                "idempotencyKey", "00000000-0000-4000-8000-000000000001");
    }

    private Map<?, ?> add(List<?> items) {
        return (Map<?, ?>) service().add(identity, input(items)).get("data");
    }

    @Test
    void propagatesReturnedVersionsAndCompletesOneBatch() {
        List<Map<String, Object>> calls = new ArrayList<>();
        when(core.mutate(any(), eq("POST"), any())).thenAnswer(call -> {
            calls.add(call.getArgument(2));
            return Map.of("data", Map.of("outcome", "APPLIED", "cart", Map.of("version", 10 + calls.size())));
        });
        var result = add(List.of(item("a"), item("b")));
        assertEquals(7, calls.get(0).get("cartVersion"));
        assertEquals(11, calls.get(1).get("cartVersion"));
        assertEquals(12, result.get("lastConfirmedCartVersion"));
        assertEquals(List.of(0, 1), result.get("appliedIndexes"));
        assertEquals(0, result.get("unconfirmedCount"));
        assertEquals("DISPLAY_CART_ONCE", result.get("nextAction"));
    }

    @Test
    void lostResponseAndRestartReplayPersistedWritesWithoutDuplicating() {
        Map<String, Map<String, Object>> storedRequests = new HashMap<>();
        Map<String, Object> storedResponses = new HashMap<>();
        int[] writes = {0};
        boolean[] loseSecondResponse = {true};
        when(core.mutate(any(), eq("POST"), any())).thenAnswer(call -> {
            Map<String, Object> args = call.getArgument(2);
            String key = (String) args.get("idempotencyKey");
            if (storedRequests.containsKey(key)) {
                assertEquals(storedRequests.get(key), args);
                return storedResponses.get(key);
            }
            storedRequests.put(key, Map.copyOf(args));
            var response = Map.of("data", Map.of("outcome", "APPLIED", "cart", Map.of("version", 8 + writes[0]++)));
            storedResponses.put(key, response);
            if (writes[0] == 2 && loseSecondResponse[0]) {
                loseSecondResponse[0] = false;
                throw new CoreApiException("CORE_UNAVAILABLE", "UNKNOWN", "test");
            }
            return response;
        });
        var items = List.of(item("a"), item("b"), item("c"));
        var failed = add(items);
        assertEquals(List.of(0), failed.get("appliedIndexes"));
        assertEquals("RETRY_ORIGINAL_BATCH_SAME_KEY_AND_BODY", failed.get("nextAction"));
        assertEquals("APPLIED", add(items).get("outcome"));
        assertEquals(3, writes[0]);
        assertEquals("APPLIED", add(items).get("outcome"));
        assertEquals(3, writes[0]);
    }

    @Test
    void stopsOnConflictWithoutSendingRemainingItems() {
        when(core.mutate(any(), eq("POST"), any()))
                .thenReturn(Map.of("data", Map.of("cart", Map.of("version", 8))))
                .thenThrow(new CoreApiException("CART_VERSION_CONFLICT", "NOT_APPLIED", "test"));
        var result = add(List.of(item("a"), item("b"), item("c")));
        assertEquals("PARTIAL", result.get("outcome"));
        assertEquals(List.of(0), result.get("appliedIndexes"));
        assertEquals(List.of(1, 2), result.get("unconfirmedIndexes"));
        verify(core, times(2)).mutate(any(), eq("POST"), any());
    }

    @Test
    void missingSnapshotPreservesAppliedItemAndStops() {
        when(core.mutate(any(), eq("POST"), any())).thenReturn(Map.of("data", Map.of("outcome", "APPLIED")));
        var result = add(List.of(item("a"), item("b")));
        assertEquals(List.of(0), result.get("appliedIndexes"));
        assertEquals(false, result.get("cartVersionKnown"));
        assertEquals("REFETCH_CART_DO_NOT_REPLAY_APPLIED", result.get("nextAction"));
        verify(core).mutate(any(), eq("POST"), any());
    }

    @Test
    void rejectsInvalidSelectionsAndMissingScopeBeforeWrites() {
        for (List<?> items : List.of(List.of(), List.of(item("a"), item("a")),
                java.util.Collections.nCopies(9, item("a")), List.of(Map.of("quantity", 1)))) {
            assertThrows(CoreApiException.class, () -> add(items));
        }
        var reader = new McpIdentity("user", "connection", Set.of("catalog:read"), Instant.now().plusSeconds(30));
        assertThrows(McpAuthException.class, () -> service().add(reader, input(List.of(item("a")))));
        verifyNoInteractions(core);
    }

    @Test
    void invalidOptionOrStockFailureDoesNotAttemptNextProduct() {
        for (String code : List.of("OUT_OF_STOCK", "INVALID_VARIANT", "IDEMPOTENCY_KEY_CONFLICT")) {
            org.mockito.Mockito.reset(core);
            when(core.mutate(any(), eq("POST"), any()))
                    .thenThrow(new CoreApiException(code, "NOT_APPLIED", "test"));
            var result = add(List.of(item("a"), item("b")));
            assertEquals("STOPPED", result.get("outcome"));
            assertEquals(List.of(), result.get("appliedIndexes"));
            assertEquals(code, ((Map<?, ?>) result.get("error")).get("code"));
            verify(core).mutate(any(), eq("POST"), any());
        }
    }

    @Test
    void oneFourAndEightItemsUseOneHandlerAndNCoreWrites() {
        for (int count : List.of(1, 4, 8)) {
            org.mockito.Mockito.reset(core);
            when(core.mutate(any(), eq("POST"), any())).thenAnswer(call -> {
                Map<String, Object> args = call.getArgument(2);
                return Map.of("data", Map.of("cart", Map.of("version", (int) args.get("cartVersion") + 1)));
            });
            var items = java.util.stream.IntStream.range(0, count).mapToObj(i -> item("p" + i)).toList();
            var result = add(items);
            assertEquals("APPLIED", result.get("outcome"));
            assertEquals(7 + count, result.get("lastConfirmedCartVersion"));
            verify(core, times(count)).mutate(any(), eq("POST"), any());
        }
    }

    @Test
    void authErrorsRemainVisibleToHost() {
        when(core.mutate(any(), eq("POST"), any()))
                .thenThrow(new CoreApiException("INVALID_TOKEN", "NOT_APPLIED", "test"));
        assertThrows(CoreApiException.class, () -> add(List.of(item("a"))));
    }

    @Test
    void newToolRequiresWriteScopeAndHasNoIntermediateUiTemplate() {
        var provider = new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(CommerceUiConfiguration.CommerceViews.class);
        var definition = new CommerceConfiguration(provider)
                .coreAddCartItems(core, McpJsonDefaults.getSchemaValidator());
        assertEquals(Set.of("cart:write"), definition.requiredScopes());
        assertFalse(definition.tool().meta().containsKey("openai/outputTemplate"));
    }
}
