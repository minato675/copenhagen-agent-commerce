package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreApiException;
import com.copenhagen.mcp.core.InternalCoreClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PurchaseHistoryServiceTest {
    private final InternalCoreClient client = mock(InternalCoreClient.class);
    private final CoreCommerceService service = new CoreCommerceService(client, McpJsonDefaults.getSchemaValidator());
    private final McpIdentity identity = new McpIdentity("user", "connection", Set.of("purchase:handoff"),
            Instant.now().plusSeconds(300));

    @Test
    void emptyInput_usesCoreDefaultsAndPreservesLastPage() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("periodStart", "2026-03-22T00:00:00Z");
        page.put("items", List.of());
        page.put("nextCursor", null);
        when(client.exchange(identity, "GET", "/internal/v1/purchase-history", Map.of(), null, null))
                .thenReturn(page);
        assertEquals(Map.of("kind", "purchase.history", "data", page), service.purchaseHistory(identity, Map.of()));
        verify(client).exchange(identity, "GET", "/internal/v1/purchase-history", Map.of(), null, null);
    }

    @Test
    void invalidInput_neverCallsCore() {
        for (Map<String, Object> input : List.<Map<String, Object>>of(Map.of("pageSize", 0),
                Map.of("pageSize", 51), Map.of("pageSize", 1.5), Map.of("cursor", "a".repeat(4097)),
                Map.of("userId", "another-user"), Map.of("pageSize", "20"))) {
            assertEquals("INVALID_ARGUMENT", assertThrows(CoreApiException.class,
                    () -> service.purchaseHistory(identity, input)).getMessage());
        }
        verifyNoInteractions(client);
    }
}
