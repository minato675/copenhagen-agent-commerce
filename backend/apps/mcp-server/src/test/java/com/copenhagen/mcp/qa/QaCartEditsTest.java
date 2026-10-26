package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.cart.CartEditService;
import com.copenhagen.mcp.cart.CartService;
import com.copenhagen.mcp.core.CoreClientException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.copenhagen.mcp.qa.QaCartStoreTest.catalog;
import static com.copenhagen.mcp.qa.QaCartStoreTest.change;
import static com.copenhagen.mcp.qa.QaCartStoreTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QaCartEditsTest {

    @TempDir
    Path directory;

    private Map<String, Object> add(QaCartStore store) throws Exception {
        store.add("user-a", request(store.get("user-a"), "request-key-add001"));
        return store.get("user-a");
    }

    private static Map<String, Object> edit(Map<String, Object> cart, String key) {
        return Map.of("cartId", cart.get("cartId"), "expectedVersion", cart.get("version"), "lineId",
                QaCatalogStore.maps(cart.get("lines")).getFirst().get("lineId"), "idempotencyKey", key);
    }

    @Test
    void updateUsesAbsoluteQuantityPreservesLineAndRepricesSelectedOption() throws Exception {
        try (QaCartStore store = new QaCartStore(catalog(), directory.resolve("cart.json"));
                QaCoreServer server = new QaCoreServer(catalog(), store)) {
            CartEditService service = new CartEditService(server.cartEditsAdapter(),
                    new CartService(server.cartAdapter(),
                    McpJsonDefaults.getSchemaValidator(), McpJsonDefaults.getMapper()));
            McpIdentity user = new McpIdentity("user-a", "connection-a", Set.of(), Instant.now().plusSeconds(60));
            Map<String, Object> input = change(change(edit(add(store), "request-key-edit01"), "quantity", 3),
                    "optionId", "option-c");
            assertEquals("APPLIED", service.update(user, input).get("status"));
            assertEquals("APPLIED", service.update(user, input).get("status"));
            Map<String, Object> cart = store.get("user-a");
            Map<String, Object> line = QaCatalogStore.maps(cart.get("lines")).getFirst();
            assertEquals(3, line.get("quantity"));
            assertEquals("option-c", line.get("optionId"));
            assertEquals(3900, line.get("lineTotal"));
            assertEquals(input.get("lineId"), line.get("lineId"));
            assertEquals(2, cart.get("version"));
            assertEquals("REVALIDATION_REQUIRED", cart.get("approvalState"));
            assertThrows(CoreClientException.class, () -> service.update(user, change(input, "quantity", 0)));
        }
    }

    @Test
    void deleteRetryAfterRestartReturnsReceiptAndFreshDeletionReportsMissing() throws Exception {
        Path ledger = directory.resolve("cart.json");
        Map<String, Object> input;
        Map<String, Object> receipt;
        try (QaCartStore store = new QaCartStore(catalog(), ledger)) {
            input = edit(add(store), "request-key-delete");
            receipt = store.change("user-a", "DELETE", input);
            assertEquals("APPLIED", receipt.get("status"));
            assertEquals(List.of(), store.get("user-a").get("lines"));
        }
        try (QaCartStore store = new QaCartStore(catalog(), ledger);
                QaCoreServer server = new QaCoreServer(catalog(), store)) {
            CartEditService service = new CartEditService(server.cartEditsAdapter(),
                    new CartService(server.cartAdapter(),
                    McpJsonDefaults.getSchemaValidator(), McpJsonDefaults.getMapper()));
            McpIdentity user = new McpIdentity("user-a", "connection-a", Set.of(), Instant.now().plusSeconds(60));
            assertEquals(receipt, service.delete(user, input));
            Map<String, Object> fresh = change(change(input, "expectedVersion", 2),
                    "idempotencyKey", "request-key-fresh1");
            assertEquals("NOT_FOUND", service.delete(user, fresh).get("reason"));
        }
    }

    @Test
    void staleModificationAndCrossUserLineNeverOverwriteCart() throws Exception {
        try (QaCartStore store = new QaCartStore(catalog(), directory.resolve("cart.json"))) {
            Map<String, Object> initial = add(store);
            Map<String, Object> update = change(change(edit(initial, "request-key-edit01"), "quantity", 3),
                    "optionId", "option-a");
            store.change("user-a", "UPDATE", update);
            assertEquals("VERSION_CONFLICT", store.change("user-a", "DELETE",
                    edit(initial, "request-key-delete")).get("reason"));
            assertEquals("KEY_CONFLICT", store.change("user-a", "UPDATE", change(update, "quantity", 4)).get("reason"));
            assertThrows(SecurityException.class, () -> store.change("user-b", "UPDATE", update));
            assertEquals("NOT_FOUND", store.change("user-a", "DELETE", change(change(edit(store.get("user-a"),
                    "request-key-missing"), "lineId", "someone-elses-line"), "expectedVersion", 2)).get("reason"));
            assertEquals(3, QaCatalogStore.maps(store.get("user-a").get("lines")).getFirst().get("quantity"));
        }
    }

    @Test
    void invalidOptionStockAndDuplicateSelectionDoNotChangeVersion() throws Exception {
        try (QaCartStore store = new QaCartStore(catalog(), directory.resolve("cart.json"))) {
            Map<String, Object> cart = add(store);
            Map<String, Object> base = edit(cart, "request-key-edit01");
            assertEquals("INVALID_OPTION", store.change("user-a", "UPDATE",
                    change(change(base, "optionId", "missing"), "quantity", 1)).get("reason"));
            assertEquals("INSUFFICIENT_STOCK", store.change("user-a", "UPDATE", change(change(change(base,
                    "idempotencyKey", "request-key-stock1"), "optionId", "option-a"), "quantity", 6)).get("reason"));
            store.add("user-a", change(request(cart, "request-key-add002"), "optionId", "option-c"));
            Map<String, Object> conflict = change(change(change(base, "idempotencyKey", "request-key-option"),
                    "expectedVersion", 2), "optionId", "option-c");
            assertEquals("DUPLICATE_SELECTION", store.change("user-a", "UPDATE", change(conflict, "quantity", 1))
                    .get("reason"));
            assertEquals(2, store.get("user-a").get("version"));
        }
    }
}
