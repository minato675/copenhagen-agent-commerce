package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreApiException;
import com.copenhagen.mcp.core.InternalCoreClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreCommerceServiceTest {
    private final InternalCoreClient client = mock(InternalCoreClient.class);
    private final CoreCommerceService service = new CoreCommerceService(client,
            McpJsonDefaults.getSchemaValidator());
    private final McpIdentity identity = new McpIdentity("user", "connection", Set.of(),
            Instant.now().plusSeconds(300));

    @Test
    void confirmedSelection_isNotForwardedAsCoreContractField() {
        String key = UUID.randomUUID().toString();
        when(client.exchange(eq(identity), eq("POST"), any(), any(), any(), eq(key)))
                .thenReturn(Map.of());
        service.mutate(identity, "POST", Map.of("cartVersion", 1, "merchantId", "m1",
                "merchantProductId", "p1", "merchantVariantId", "v1", "quantity", 1,
                "idempotencyKey", key, "selectionBasis", "USER_SELECTED"));
        verify(client).exchange(eq(identity), eq("POST"), eq("/internal/v1/carts/current/items"),
                eq(Map.of()), eq(Map.of("cartVersion", 1, "merchantId", "m1", "merchantProductId", "p1",
                        "merchantVariantId", "v1", "quantity", 1)), eq(key));
    }

    @Test
    void addWithoutUserSelectionBasis_neverCallsCore() {
        Map<String, Object> args = Map.of("cartVersion", 1, "merchantId", "m1",
                "merchantProductId", "p1", "merchantVariantId", "v1", "quantity", 1,
                "idempotencyKey", UUID.randomUUID().toString());
        assertThrows(CoreApiException.class, () -> service.mutate(identity, "POST", args));
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void relatedSearch_preservesFiltersAndSkipsDuplicateOffers() throws Exception {
        Map<String, Object> response = json("""
                {"products":[{"name":"컵","category":{"id":"cup","name":"컵"},
                "offers":[{"merchantId":"m1","merchantName":"상점","merchantProductId":"p1",
                "priceAmount":2000,"currency":"KRW","availability":"IN_STOCK"}]}],
                "filters":{"categories":[],"merchants":[]},"nextCursor":null,"failedMerchants":[],
                "checkedAt":"2026-09-17T00:00:00Z"}
                """);
        when(client.exchange(eq(identity), eq("GET"), eq("/internal/v1/catalog/products"), any(), isNull(), isNull()))
                .thenReturn(response);
        Map<?, ?> data = (Map<?, ?>) service.search(identity, Map.of("query", "물컵",
                "relatedQueries", java.util.List.of("텀블러"), "maxPrice", 10000)).get("data");
        Map<?, ?> related = (Map<?, ?>) ((java.util.List<?>) data.get("relatedSearches")).getFirst();
        assertEquals(java.util.List.of(), related.get("products"));
        verify(client).exchange(eq(identity), eq("GET"), eq("/internal/v1/catalog/products"),
                eq(Map.of("query", "텀블러", "maxPrice", 10000)), isNull(), isNull());
    }

    @Test
    void relatedSearch_isBoundedAndNotRepeatedForCursor() throws Exception {
        Map<String, Object> empty = json("""
                {"products":[],"filters":{"categories":[],"merchants":[]},"nextCursor":null,
                "failedMerchants":[],"checkedAt":"2026-09-17T00:00:00Z"}
                """);
        when(client.exchange(eq(identity), eq("GET"), eq("/internal/v1/catalog/products"), any(), isNull(), isNull()))
                .thenReturn(empty);
        assertThrows(CoreApiException.class, () -> service.search(identity,
                Map.of("relatedQueries", java.util.List.of("a", "b", "c"))));
        service.search(identity, Map.of("query", "컵", "cursor", "next",
                "relatedQueries", java.util.List.of("텀블러")));
        verify(client, org.mockito.Mockito.times(1)).exchange(any(), any(), any(), any(), isNull(), isNull());
    }

    @Test
    void relatedSearch_partialFailureKeepsPrimaryResultsButAuthFailurePropagates() throws Exception {
        Map<String, Object> response = json("""
                {"products":[],"filters":{"categories":[],"merchants":[]},"nextCursor":"primary-next",
                "failedMerchants":[],"checkedAt":"2026-09-17T00:00:00Z"}
                """);
        when(client.exchange(eq(identity), eq("GET"), eq("/internal/v1/catalog/products"), any(), isNull(), isNull()))
                .thenReturn(response).thenThrow(new CoreApiException("CORE_UNAVAILABLE", "NOT_APPLIED", "test"));
        Map<?, ?> data = (Map<?, ?>) service.search(identity, Map.of("query", "컵",
                "relatedQueries", java.util.List.of("텀블러"))).get("data");
        assertEquals("primary-next", data.get("nextCursor"));
        Map<?, ?> related = (Map<?, ?>) ((java.util.List<?>) data.get("relatedSearches")).getFirst();
        assertEquals(true, related.get("unavailable"));
        org.mockito.Mockito.reset(client);
        when(client.exchange(eq(identity), eq("GET"), eq("/internal/v1/catalog/products"), any(), isNull(), isNull()))
                .thenReturn(response).thenThrow(new CoreApiException("AUTHENTICATION_REQUIRED", "NOT_APPLIED", "test"));
        assertEquals("AUTHENTICATION_REQUIRED", assertThrows(CoreApiException.class,
                () -> service.search(identity, Map.of("query", "컵",
                        "relatedQueries", java.util.List.of("텀블러")))).getMessage());
    }

    @Test
    void relatedSearch_keepsDistinctProductsAndPerQueryCursors() throws Exception {
        Map<String, Object> direct = json("""
                {"products":[{"name":"물컵","category":{"id":"cup","name":"컵"},
                "offers":[{"merchantId":"m1","merchantName":"상점","merchantProductId":"p1",
                "priceAmount":2000,"currency":"KRW","availability":"IN_STOCK"}]}],
                "filters":{"categories":[],"merchants":[]},"nextCursor":"direct-next","failedMerchants":[],
                "checkedAt":"2026-09-17T00:00:00Z"}
                """);
        Map<String, Object> related = json("""
                {"products":[{"name":"텀블러","category":{"id":"cup","name":"컵"},
                "offers":[{"merchantId":"m1","merchantName":"상점","merchantProductId":"p2",
                "priceAmount":3000,"currency":"KRW","availability":"IN_STOCK"}]}],
                "filters":{"categories":[],"merchants":[]},"nextCursor":"related-next","failedMerchants":[],
                "checkedAt":"2026-09-17T00:00:00Z"}
                """);
        when(client.exchange(eq(identity), eq("GET"), eq("/internal/v1/catalog/products"), any(), isNull(), isNull()))
                .thenReturn(direct, related);
        Map<?, ?> data = (Map<?, ?>) service.search(identity, Map.of("query", "물컵",
                "relatedQueries", java.util.List.of("텀블러"))).get("data");
        Map<?, ?> alternative = (Map<?, ?>) ((java.util.List<?>) data.get("relatedSearches")).getFirst();
        assertEquals("direct-next", data.get("nextCursor"));
        assertEquals("related-next", alternative.get("nextCursor"));
        assertEquals(Map.of("query", "텀블러"), alternative.get("query"));
        assertEquals(1, ((java.util.List<?>) alternative.get("products")).size());
        assertEquals("물컵", ((Map<?, ?>) ((java.util.List<?>) data.get("products")).getFirst()).get("name"));
    }

    @Test
    void search_allCategories_keepsCursorAndPartialResultsWithoutPrivateFields() throws Exception {
        Map<String, Object> response = json("""
                {"products":[{"name":"신발","brand":"브랜드","category":{"id":"shoes","name":"신발"},
                "imageUrl":null,"offers":[{"merchantId":"m1","merchantName":"상점","merchantProductId":"daiso:1001712",
                "priceAmount":12000,"currency":"KRW","availability":"IN_STOCK","privateToken":"excluded"}]}],
                "filters":{"categories":[],"merchants":[]},"nextCursor":"opaque-cursor","failedMerchants":["m2"],
                "checkedAt":"2026-09-17T00:00:00Z","userId":123}
                """);
        when(client.exchange(eq(identity), eq("GET"), eq("/internal/v1/catalog/products"), any(), isNull(), isNull()))
                .thenReturn(response);
        Map<String, Object> output = service.search(identity, Map.of("query", "신발", "pageSize", 30));
        assertEquals("catalog.search", output.get("kind"));
        assertFalse(output.toString().contains("privateToken"));
        assertFalse(output.toString().contains("userId"));
        assertEquals("opaque-cursor", ((Map<?, ?>) output.get("data")).get("nextCursor"));
    }

    @Test
    void catalogIdentifier_colonAcceptedInInputAndOutputButUnsafeCharactersRejected() {
        String sku = "kurly:1000025270-M00000008360";
        assertEquals(sku, CommerceProjection.id(sku));
        assertEquals(true, sku.matches((String) CommerceSchemas.ID.get("pattern")));
        for (String unsafe : java.util.List.of("../x", "a/b", "a?b", "a#b", "a%2fb", "a".repeat(97))) {
            assertThrows(CoreApiException.class, () -> CommerceProjection.id(unsafe));
            assertFalse(unsafe.matches((String) CommerceSchemas.ID.get("pattern")));
        }
    }

    @Test
    void mutation_atomicPatchAndDelete_useFinalQuantityAndQueryVersion() {
        String key = UUID.randomUUID().toString();
        when(client.exchange(eq(identity), any(), any(), any(), any(), eq(key))).thenReturn(Map.of());
        service.mutate(identity, "PATCH", Map.of("cartItemId", "item1", "cartVersion", 12,
                "merchantVariantId", "variant2", "quantity", 3, "idempotencyKey", key));
        verify(client).exchange(identity, "PATCH", "/internal/v1/carts/current/items/item1", Map.of(),
                Map.of("cartVersion", 12, "merchantVariantId", "variant2", "quantity", 3), key);
        when(client.exchange(eq(identity), eq("DELETE"), any(), any(), isNull(), eq(key))).thenReturn(Map.of());
        Map<String, Object> output = service.mutate(identity, "DELETE", Map.of("cartItemId", "item1",
                "cartVersion", 13, "idempotencyKey", key));
        verify(client).exchange(identity, "DELETE", "/internal/v1/carts/current/items/item1",
                Map.of("cartVersion", 13), null, key);
        Map<?, ?> result = (Map<?, ?>) output.get("data");
        assertEquals("APPLIED", result.get("outcome"));
        assertNull(result.get("cart"));
        assertEquals("REFETCH_CART", result.get("nextAction"));
    }

    @Test
    void purchase_withoutAppLink_keepsExpiredSnapshotForStatusRecovery() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> input = Map.of("cartId", "cart1", "cartVersion", 12, "idempotencyKey", key);
        when(client.exchange(eq(identity), eq("POST"), eq("/internal/v1/purchase-requests"), any(), any(), eq(key)))
                .thenReturn(Map.of("purchaseRequestId", "request1", "status", "PENDING_ADDRESS",
                        "cartId", "cart1", "cartVersion", 12, "expiresAt", "2020-01-01T00:00:00Z",
                        "nextAction", "SELECT_SHIPPING_ADDRESS"));
        Map<?, ?> data = (Map<?, ?>) service.purchase(identity, input).get("data");
        assertEquals("request1", data.get("purchaseRequestId"));
        assertEquals("2020-01-01T00:00:00Z", data.get("expiresAt"));
        assertFalse(data.containsKey("appLink"));
        verify(client).exchange(identity, "POST", "/internal/v1/purchase-requests", Map.of(),
                Map.of("cartId", "cart1", "cartVersion", 12), key);
    }

    @Test
    void purchase_replayedLegacyResponse_doesNotExposeAppLink() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> input = Map.of("cartId", "cart1", "cartVersion", 12, "idempotencyKey", key);
        when(client.exchange(eq(identity), eq("POST"), eq("/internal/v1/purchase-requests"), any(), any(), eq(key)))
                .thenReturn(Map.of("purchaseRequestId", "request1", "status", "PENDING_ADDRESS",
                        "cartId", "cart1", "cartVersion", 12, "expiresAt", "2020-01-01T00:00:00Z",
                        "appLink", "https://evil.example/purchase/request1", "nextAction", "SELECT_SHIPPING_ADDRESS"));
        assertFalse(((Map<?, ?>) service.purchase(identity, input).get("data")).containsKey("appLink"));
    }

    @Test
    void purchase_malformedSuccess_keepsOutcomeUnknownForSameKeyRecovery() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> input = Map.of("cartId", "cart1", "cartVersion", 12, "idempotencyKey", key);
        for (String invalidId : java.util.List.of("../unsafe", "request1")) {
            when(client.exchange(eq(identity), eq("POST"), any(), any(), any(), eq(key)))
                    .thenReturn(Map.of("purchaseRequestId", invalidId, "status", "PENDING_ADDRESS",
                            "cartId", "cart1", "cartVersion", 12, "expiresAt", "invalid-date",
                            "nextAction", "SELECT_SHIPPING_ADDRESS"));
            var error = assertThrows(CoreApiException.class, () -> service.purchase(identity, input));
            assertEquals("UNKNOWN", ((Map<?, ?>) error.body().get("error")).get("outcome"));
        }
    }

    @Test
    void cart_unknownTotals_remainNull() throws Exception {
        var cart = CommerceProjection.cart(json("""
                {"cartId":"cart1","version":1,"items":[],"merchantSummaries":[],
                 "summary":{"currency":"KRW","itemSubtotalAmount":0,"discountAmount":null,
                 "shippingAmount":null,"totalAmount":null,"status":"INCOMPLETE"}}
                """));
        assertNull(((Map<?, ?>) cart.get("summary")).get("totalAmount"));
    }

    private Map<String, Object> json(String value) throws Exception {
        return McpJsonDefaults.getMapper().readValue(value, new TypeRef<>() { });
    }
}
