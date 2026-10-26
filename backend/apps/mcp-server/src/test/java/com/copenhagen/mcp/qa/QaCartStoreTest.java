package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.cart.CartService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QaCartStoreTest {

    @TempDir
    Path directory;

    static QaCatalogStore catalog() {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("productId", "product-a");
        product.put("name", "QA 상품");
        product.put("brand", "QA");
        product.put("category", "QA");
        product.put("imageUrl", "");
        product.put("stockQuantity", 5);
        product.put("optionsRequired", true);
        product.put("offers", List.of(Map.of("merchantId", "qa-a", "merchantName", "가상몰 A",
                "price", 1000, "currency", "KRW", "availability", "IN_STOCK")));
        product.put("variants", List.of(Map.of("optionId", "option-a", "label", "옵션 A",
                "prices", Map.of("qa-a", 1100), "availability", "IN_STOCK", "purchaseAllowed", true),
                Map.of("optionId", "option-b", "label", "품절 B", "prices", Map.of("qa-a", 1200),
                        "availability", "OUT_OF_STOCK", "purchaseAllowed", false),
                Map.of("optionId", "option-c", "label", "옵션 C", "prices", Map.of("qa-a", 1300),
                        "availability", "IN_STOCK", "purchaseAllowed", true)));
        return new QaCatalogStore(List.of(product), Set.of());
    }

    static Map<String, Object> request(Map<String, Object> cart, String key) {
        return Map.of("cartId", cart.get("cartId"), "expectedVersion", cart.get("version"),
                "idempotencyKey", key, "productId", "product-a", "merchantId", "qa-a",
                "optionId", "option-a", "quantity", 2);
    }

    static Map<String, Object> change(Map<String, Object> source, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put(key, value);
        return result;
    }

    @Test
    void retry_afterRestartReturnsReceiptWithoutAddingAgain() throws Exception {
        Path ledger = directory.resolve("cart.json");
        Map<String, Object> request;
        Map<String, Object> receipt;
        try (QaCartStore store = new QaCartStore(catalog(), ledger)) {
            request = request(store.get("user-a"), "request-key-000001");
            receipt = store.add("user-a", request);
            assertEquals("APPLIED", receipt.get("status"));
            assertEquals(receipt, store.add("user-a", request));
            assertEquals("KEY_CONFLICT", store.add("user-a", change(request, "quantity", 3)).get("reason"));
        }
        try (QaCartStore store = new QaCartStore(catalog(), ledger)) {
            assertEquals(receipt, store.add("user-a", request));
            assertEquals(2, QaCatalogStore.maps(store.get("user-a").get("lines")).getFirst().get("quantity"));
            assertEquals("APPLIED", store.add("user-a", request(store.get("user-a"), "request-key-000002"))
                    .get("status"));
            assertEquals(4, QaCatalogStore.maps(store.get("user-a").get("lines")).getFirst().get("quantity"));
        }
    }

    @Test
    void view_legacyMerchantNames_updatesOnlyDisplayAndPreservesLedgerAndRetryReceipt() throws Exception {
        Path ledger = directory.resolve("legacy-cart.json");
        Map<String, Object> request;
        Map<String, Object> receipt;
        try (QaCartStore store = new QaCartStore(catalog(), ledger)) {
            request = request(store.get("user-a"), "legacy-request-0001");
            receipt = store.add("user-a", request);
        }
        // Reproduce a ledger and receipt saved before the QA display names changed.
        Files.writeString(ledger, Files.readString(ledger).replace("노르드 마켓", "가상몰 A"));
        Map<String, Object> legacyReceipt = McpJsonDefaults.getMapper().readValue(
                McpJsonDefaults.getMapper().writeValueAsString(receipt).replace("노르드 마켓", "가상몰 A"),
                new TypeRef<>() { });
        byte[] original = Files.readAllBytes(ledger);
        try (QaCartStore store = new QaCartStore(catalog(), ledger)) {
            Map<?, ?> displayed = (Map<?, ?>) store.view("user-a").get("cart");
            Map<String, Object> line = QaCatalogStore.maps(displayed.get("lines")).getFirst();
            assertEquals("qa-a", line.get("merchantId"));
            assertEquals("노르드 마켓", line.get("merchantName"));
            assertEquals("가상몰 A", QaCatalogStore.maps(store.get("user-a").get("lines"))
                    .getFirst().get("merchantName"));
            assertEquals(legacyReceipt, store.add("user-a", request));
            assertEquals(1, store.get("user-a").get("version"));
            assertArrayEquals(original, Files.readAllBytes(ledger));
        }
        assertArrayEquals(original, Files.readAllBytes(ledger));
    }

    @Test
    void add_rejectsMissingInvalidUnavailableOptionsAndInsufficientStock() throws Exception {
        try (QaCartStore store = new QaCartStore(catalog(), directory.resolve("cart.json"))) {
            Map<String, Object> cart = store.get("user-a");
            for (Map.Entry<String, String> option : Map.of("", "OPTION_REQUIRED", "wrong", "INVALID_OPTION",
                    "option-b", "UNAVAILABLE").entrySet()) {
                Map<String, Object> request = change(request(cart, "request-key-option" + option.getValue()),
                        "optionId", option.getKey());
                assertEquals(option.getValue(), store.add("user-a", request).get("reason"));
            }
            assertEquals("INSUFFICIENT_STOCK", store.add("user-a", change(request(cart, "request-key-stock"),
                    "quantity", 6)).get("reason"));
            assertEquals(List.of(), store.get("user-a").get("lines"));
            assertThrows(IllegalArgumentException.class, () -> store.add("user-a",
                    change(request(cart, "request-key-invalid"), "quantity", -1)));
        }
    }

    @Test
    void simultaneousRequestsAndRetriesAreSerializedAndOtherUsersCannotUseCart() throws Exception {
        try (QaCartStore store = new QaCartStore(catalog(), directory.resolve("cart.json"));
                java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Map<String, Object> request = request(store.get("user-a"), "request-key-000001");
            Future<Map<String, Object>> first = executor.submit(() -> store.add("user-a", request));
            Future<Map<String, Object>> second = executor.submit(() -> store.add("user-a", request));
            assertEquals(first.get(), second.get());
            assertEquals("VERSION_CONFLICT", store.add("user-a", change(request, "idempotencyKey",
                    "request-key-000002")).get("reason"));
            assertThrows(SecurityException.class, () -> store.add("user-b", request));
            assertNotEquals(store.get("user-a").get("cartId"), store.get("user-b").get("cartId"));
            assertEquals(List.of(), store.get("user-b").get("lines"));
        }
    }

    @Test
    void actualCoreHttpAdapterPreservesOwnerAndIntegerRequestEcho() throws Exception {
        try (QaCartStore store = new QaCartStore(catalog(), directory.resolve("cart.json"));
                QaCoreServer core = new QaCoreServer(catalog(), store)) {
            CartService service = new CartService(core.cartAdapter(), McpJsonDefaults.getSchemaValidator(),
                    McpJsonDefaults.getMapper());
            McpIdentity user = new McpIdentity("user-a", "connection-a", Set.of(), Instant.now().plusSeconds(60));
            Map<String, Object> input = request(store.get("user-a"), "request-key-http01");
            assertEquals("APPLIED", service.add(user, input).get("status"));
            assertEquals("APPLIED", service.add(user, input).get("status"));
            assertEquals(1, store.get("user-a").get("version"));
        }
    }

    @Test
    void ledgerCannotBeOpenedByTwoWriters() throws Exception {
        Path ledger = directory.resolve("cart.json");
        try (QaCartStore store = new QaCartStore(catalog(), ledger)) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,
                    () -> new QaCartStore(catalog(), ledger));
            assertEquals(List.of(), store.get("user-a").get("lines"));
        }
    }
}
