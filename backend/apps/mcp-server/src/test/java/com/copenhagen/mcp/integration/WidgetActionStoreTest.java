package com.copenhagen.mcp.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.tool.WidgetActionTrace;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WidgetActionStoreTest {
    private final Instant now = Instant.parse("2026-09-19T00:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final WidgetActionStore store = new WidgetActionStore(McpJsonDefaults.getMapper(),
            McpJsonDefaults.getSchemaValidator(), clock);

    private McpIdentity identity(String user, String connection, String scope) {
        return new McpIdentity(user, connection, Set.of(scope), now.plusSeconds(3600));
    }

    private String prepare(McpIdentity owner, String tool, Map<String, Object> args) {
        when(clock.instant()).thenReturn(now);
        var output = store.prepare(owner, Map.of("tool", tool, "arguments", args));
        return (String) ((Map<?, ?>) output.get("data")).get("reference");
    }

    @Test
    void selectionIsSnapshottedAndIsolatedFromOtherCardsAndAccounts() {
        var owner = identity("one", "connection", "catalog:read");
        Map<String, Object> args = new HashMap<>(Map.of("merchantId", "merchant-a", "merchantProductId", "onion"));
        String reference = prepare(owner, "get_product_detail", args);
        args.put("merchantProductId", "changed");
        prepare(owner, "search_products", Map.of("query", "empty"));
        var resolved = store.resolve(owner, reference);
        assertEquals(Map.of("tool", "get_product_detail", "arguments",
                Map.of("merchantId", "merchant-a", "merchantProductId", "onion")), resolved.get("data"));
        assertThrows(RuntimeException.class,
                () -> store.resolve(identity("two", "connection", "catalog:read"), reference));
        assertThrows(RuntimeException.class,
                () -> store.resolve(identity("one", "other", "catalog:read"), reference));
        when(clock.instant()).thenReturn(now.plusSeconds(900));
        assertThrows(RuntimeException.class, () -> store.resolve(owner, reference));
    }

    @Test
    void writeArgumentsAreStableAndScopesAreCheckedAgainOnResolve() {
        var owner = identity("one", "connection", "cart:write");
        var args = Map.<String, Object>of("cartVersion", 2, "cartItemId", "item-1",
                "idempotencyKey", "00000000-0000-4000-8000-000000000001");
        String reference = prepare(owner, "delete_cart_item", args);
        assertEquals(store.resolve(owner, reference), store.resolve(owner, reference));
        assertEquals(Map.of("tool", "delete_cart_item", "arguments", args),
                store.resolve(owner, reference).get("data"));
        assertThrows(RuntimeException.class,
                () -> store.resolve(identity("one", "connection", "cart:read"), reference));
        assertThrows(RuntimeException.class, () -> prepare(owner, "delete_cart_item", Map.of()));
        assertThrows(RuntimeException.class, () -> prepare(owner, "unknown", Map.of()));
    }
    @Test
    void traceSeparatesFailuresWithoutExposingRawValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(WidgetActionTrace.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var owner = identity("private-owner", "private-connection", "catalog:read");
            String reference = prepare(owner, "search_products", Map.of("query", "private-product"));
            when(clock.instant()).thenReturn(now.plusSeconds(13));
            store.resolve(owner, reference);
            assertThrows(RuntimeException.class,
                    () -> store.resolve(identity("other", "private-connection", "catalog:read"), reference));
            assertThrows(RuntimeException.class,
                    () -> store.resolve(identity("private-owner", "other", "catalog:read"), reference));
            assertThrows(RuntimeException.class, () -> store.resolve(owner, "missing-reference"));
            when(clock.instant()).thenReturn(now.plusSeconds(900));
            assertThrows(RuntimeException.class, () -> store.resolve(owner, reference));
            assertThrows(com.copenhagen.mcp.auth.McpAuthException.class,
                    () -> store.prepare(owner, Map.of("tool", "get_cart", "arguments", Map.of())));
            store.prepare(owner, Map.of("tool", "search_products", "arguments", Map.of()));
            assertThrows(RuntimeException.class, () -> store.resolve(owner, reference));
            String first = appender.list.get(0).getFormattedMessage();
            String second = appender.list.get(1).getFormattedMessage();
            String referenceHash = first.split("referenceHash=")[1].split(",")[0];
            assertTrue(second.contains("referenceHash=" + referenceHash));
            assertFalse(first.contains(reference));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
        for (String reason : Set.of("PREPARED", "RESOLVED", "OWNER_MISMATCH", "CONNECTION_MISMATCH",
                "REFERENCE_NOT_FOUND", "REFERENCE_EXPIRED", "INSUFFICIENT_SCOPE", "EXPIRED_REMOVED")) {
            assertTrue(logs.contains("reason=" + reason), reason);
        }
        assertTrue(logs.contains("ageMs=13000"));
        assertFalse(logs.contains("private-owner"));
        assertFalse(logs.contains("private-connection"));
        assertFalse(logs.contains("private-product"));
        assertFalse(logs.contains("missing-reference"));
    }
    @Test
    void exactIntentFallbackPreservesArgumentsAndRejectsAmbiguousOrForeignSelections() {
        when(clock.instant()).thenReturn(now);
        var owner = identity("one", "connection", "cart:write");
        String intent = "bread final quantity 3";
        var args = Map.<String, Object>of("cartVersion", 2, "cartItemId", "item-1",
                "idempotencyKey", "00000000-0000-4000-8000-000000000001");
        var input = Map.<String, Object>of("tool", "delete_cart_item", "arguments", args, "intent", intent);
        store.prepare(owner, input);
        assertEquals(Map.of("tool", "delete_cart_item", "arguments", args),
                store.resolveIntent(owner, intent).get("data"));
        assertThrows(RuntimeException.class, () -> store.resolveIntent(owner, "bread"));
        assertThrows(RuntimeException.class,
                () -> store.resolveIntent(identity("other", "connection", "cart:write"), intent));
        assertThrows(RuntimeException.class,
                () -> store.resolveIntent(identity("one", "other", "cart:write"), intent));
        store.prepare(owner, input);
        assertThrows(RuntimeException.class, () -> store.resolveIntent(owner, intent));
    }

    @Test
    void intentFallbackDoesNotReviveExpiredSelection() {
        when(clock.instant()).thenReturn(now);
        var owner = identity("one", "connection", "catalog:read");
        store.prepare(owner, Map.of("tool", "search_products", "arguments", Map.of(), "intent", "search bread"));
        when(clock.instant()).thenReturn(now.plusSeconds(900));
        assertThrows(RuntimeException.class, () -> store.resolveIntent(owner, "search bread"));
    }
}
