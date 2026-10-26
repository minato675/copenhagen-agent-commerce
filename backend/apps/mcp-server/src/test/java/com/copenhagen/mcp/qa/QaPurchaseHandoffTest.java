package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.handoff.HandoffProperties;
import com.copenhagen.mcp.handoff.PurchaseHandoffService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QaPurchaseHandoffTest {
    @TempDir
    Path directory;

    private static Map<String, Object> request(Map<String, Object> cart) {
        return Map.of("cartId", cart.get("cartId"), "expectedVersion", cart.get("version"),
                "idempotencyKey", "handoff-request-0001");
    }

    @Test
    void durableConcurrentRetryReturnsSameReferenceAndExpiry() throws Exception {
        Path file = directory.resolve("cart.json");
        Instant now = Instant.now();
        Map<String, Object> input;
        Map<String, Object> receipt;
        try (QaCartStore store = new QaCartStore(QaCartStoreTest.catalog(), file);
                var executor = Executors.newFixedThreadPool(2)) {
            store.add("a", QaCartStoreTest.request(store.get("a"), "cart-request-0001"));
            input = request(store.get("a"));
            var first = executor.submit(() -> store.prepareHandoff("a", "connection-a", input, now));
            var second = executor.submit(() -> store.prepareHandoff("a", "connection-a", input, now));
            receipt = first.get();
            assertEquals("READY", receipt.get("status"));
            assertEquals(receipt, second.get());
            assertThrows(SecurityException.class, () -> store.prepareHandoff("b", "connection-a", input, now));
            assertNotEquals(receipt.get("purchaseRef"), store.prepareHandoff("a", "connection-b", input, now)
                    .get("purchaseRef"));
        }
        try (QaCartStore store = new QaCartStore(QaCartStoreTest.catalog(), file)) {
            assertEquals(receipt, store.prepareHandoff("a", "connection-a", input, now.plusSeconds(10)));
            Map<String, Object> expired = store.prepareHandoff("a", "connection-a", input, now.plusSeconds(600));
            assertEquals("EXPIRED", expired.get("status"));
            assertNull(expired.get("appLink"));
            assertEquals("KEY_CONFLICT", store.prepareHandoff("a", "connection-a",
                    QaCartStoreTest.change(input, "expectedVersion", 2), now).get("reason"));
            store.add("a", QaCartStoreTest.request(store.get("a"), "cart-request-0002"));
            assertEquals("VERSION_CONFLICT", store.prepareHandoff("a", "connection-a", input, now).get("reason"));
        }
    }

    @Test
    void emptyAndMultipleMerchantsAreRejectedWithoutLink() {
        Map<String, Object> cart = new LinkedHashMap<>(Map.of("cartId", "cart-a", "version", 0, "lines", List.of()));
        assertEquals("EMPTY_CART", QaPurchaseHandoff.prepare(cart, new LinkedHashMap<>(), "a", request(cart),
                Instant.now()).get("reason"));
        cart.put("lines", List.of(Map.of("merchantId", "one"), Map.of("merchantId", "two")));
        Map<String, Object> result = QaPurchaseHandoff.prepare(cart, new LinkedHashMap<>(), "a", request(cart),
                Instant.now());
        assertEquals("MULTIPLE_MERCHANTS", result.get("reason"));
        assertNull(result.get("appLink"));
    }

    @Test
    void httpAdapterValidatesIdentityAndPreservesRequestOnRetry() throws Exception {
        try (QaCartStore store = new QaCartStore(QaCartStoreTest.catalog(), directory.resolve("cart.json"));
                QaCoreServer core = new QaCoreServer(QaCartStoreTest.catalog(), store)) {
            store.add("a", QaCartStoreTest.request(store.get("a"), "cart-request-0001"));
            PurchaseHandoffService service = new PurchaseHandoffService(core.handoffAdapter(),
                    new HandoffProperties(true, Set.of("qa:handoff:write"), URI.create(QaPurchaseHandoff.LINK_BASE)),
                    McpJsonDefaults.getSchemaValidator(), McpJsonDefaults.getMapper(), Clock.systemUTC());
            McpIdentity identity = new McpIdentity("a", "connection-a", Set.of(), Instant.now().plusSeconds(60));
            Map<String, Object> input = request(store.get("a"));
            Map<String, Object> result = service.prepare(identity, input);
            assertEquals("READY", result.get("status"));
            assertEquals(result, service.prepare(identity, input));
        }
    }
}
