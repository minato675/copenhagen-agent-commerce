package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreApiException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingBatchServiceTest {
    private final CoreCommerceService commerce = mock(CoreCommerceService.class);
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-09-21T00:00:00Z");
    private final McpIdentity identity = new McpIdentity("user", "connection", Set.of("catalog:read"),
            now.plusSeconds(3600));

    @Test
    void searchOnce_displayReusesSnapshotAndRejectsOtherOwnerAndExpiredReference() {
        when(clock.instant()).thenReturn(now);
        ShoppingBatchService service = service();
        when(commerce.search(eq(identity), any())).thenAnswer(invocation -> Map.of("data",
                Map.of("products", List.of(Map.of("name", invocation.getArgument(1, Map.class).get("query"))))));
        Map<?, ?> result = (Map<?, ?>) service.search(identity,
                Map.of("queries", List.of(Map.of("query", "bread"), Map.of("query", "cheese")))).get("data");
        Map<String, Object> reference = Map.of("searchReference", result.get("searchReference"));
        assertEquals(service.display(identity, reference), service.display(identity, reference));
        verify(commerce, times(2)).search(eq(identity), any());
        McpIdentity other = new McpIdentity("other", "connection", Set.of("catalog:read"), now.plusSeconds(3600));
        assertThrows(CoreApiException.class, () -> service.display(other, reference));
        McpIdentity reconnected = new McpIdentity("user", "other", Set.of("catalog:read"), now.plusSeconds(3600));
        assertThrows(CoreApiException.class, () -> service.display(reconnected, reference));
        when(clock.instant()).thenReturn(now.plusSeconds(300));
        assertThrows(CoreApiException.class, () -> service.display(identity, reference));
    }

    @Test
    void batchPreservesPartialFailuresButDoesNotHideAuthenticationErrors() {
        when(clock.instant()).thenReturn(now);
        ShoppingBatchService service = service();
        when(commerce.detail(eq(identity), any())).thenReturn(Map.of("data", Map.of("name", "bread")))
                .thenThrow(CoreCommerceService.failure("CORE_UNAVAILABLE", false));
        Map<String, Object> input = Map.of("products", List.of(
                Map.of("merchantId", "a", "merchantProductId", "bread"),
                Map.of("merchantId", "a", "merchantProductId", "cheese")));
        Map<?, ?> data = (Map<?, ?>) service.details(identity, input).get("data");
        List<?> results = (List<?>) data.get("results");
        assertEquals("CORE_UNAVAILABLE", ((Map<?, ?>) results.get(1)).get("error"));
        when(commerce.detail(eq(identity), any()))
                .thenThrow(CoreCommerceService.failure("INSUFFICIENT_SCOPE", false));
        assertThrows(CoreApiException.class, () -> service.details(identity, input));
        assertThrows(CoreApiException.class, () -> service.search(identity, Map.of("queries", List.of())));
        assertThrows(CoreApiException.class, () -> service.search(identity,
                Map.of("queries", java.util.Collections.nCopies(9, Map.of("query", "bread")))));
    }

    private ShoppingBatchService service() {
        return new ShoppingBatchService(commerce, McpJsonDefaults.getSchemaValidator(),
                McpJsonDefaults.getMapper(), clock);
    }
}
