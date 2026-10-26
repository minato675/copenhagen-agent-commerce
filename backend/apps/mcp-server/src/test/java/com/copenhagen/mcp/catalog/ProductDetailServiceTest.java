package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.copenhagen.mcp.catalog.CatalogFixture.change;
import static com.copenhagen.mcp.catalog.CatalogFixture.envelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductDetailServiceTest {

    private static final McpIdentity USER = new McpIdentity("user-a", "connection-a", Set.of(),
            Instant.parse("2100-01-01T00:00:00Z"));
    private static final Map<String, Object> INPUT = Map.of("productId", "qa-product-1", "merchantId", "qa-a");

    static Map<String, Object> product() {
        Map<String, Object> product = new LinkedHashMap<>(CatalogFixture.product());
        product.put("offer", ((List<?>) product.remove("offers")).getFirst());
        product.put("description", "<script>untrusted product text</script>");
        product.put("specs", List.of());
        product.put("variants", List.of());
        product.put("optionsComplete", true);
        product.put("optionsRequired", null);
        product.put("selectionStatus", "UNKNOWN");
        product.put("selectedOptionId", null);
        product.put("stockQuantity", null);
        product.put("shipping", change(Map.of("status", "UNKNOWN", "description", ""), "fee", null));
        return product;
    }

    static Map<String, Object> response(Map<String, Object> request, Map<String, Object> product) {
        return change(Map.of("kind", "catalog.detail", "mode", "QA", "status", product == null ? "NOT_FOUND" : "FOUND",
                "request", request, "checkedAt", "2026-09-16T00:00:00Z"), "product", product);
    }

    @Test
    void details_preserveUnknownStockShippingAndUntrustedText() {
        Map<String, Object> result = service(response(INPUT, product())).get(USER, INPUT);
        Map<?, ?> product = (Map<?, ?>) result.get("product");
        assertNull(product.get("stockQuantity"));
        assertNull(((Map<?, ?>) product.get("shipping")).get("fee"));
        assertEquals("<script>untrusted product text</script>", product.get("description"));
        assertEquals("NOT_FOUND", service(response(INPUT, null)).get(USER, INPUT).get("status"));
    }

    @Test
    void details_rejectInvalidReferencesBeforeCallingCore() {
        AtomicInteger calls = new AtomicInteger();
        ProductDetailService service = service((user, request) -> {
            calls.incrementAndGet();
            return null;
        });
        for (Map<String, Object> bad : List.of(Map.<String, Object>of(), change(INPUT, "productId", "https://evil"),
                change(INPUT, "optionId", ""), change(INPUT, "userId", "other"), change(INPUT, "quantity", 1))) {
            assertEquals(ToolErrorCode.INVALID_ARGUMENT,
                    assertThrows(CoreClientException.class, () -> service.get(USER, bad)).code());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void details_validateOwnerReferencesBoundsAndUnknownShipping() {
        assertThrows(McpAuthException.class, () -> service((user, request) ->
                envelope("other-user", "connection-a", response(INPUT, product()))).get(USER, INPUT));
        for (Map<String, Object> invalid : List.of(change(product(), "productId", "other-product"),
                change(product(), "description", "x".repeat(4001)),
                change(product(), "shipping", Map.of("status", "UNKNOWN", "fee", 0, "description", "")),
                change(product(), "selectedOptionId", "nonexistent"),
                change(product(), "offer", change((Map<String, Object>) product().get("offer"),
                        "merchantId", "qa-b")))) {
            assertEquals(ToolErrorCode.CORE_INVALID_RESPONSE,
                    assertThrows(CoreClientException.class, () -> service(response(INPUT, invalid)).get(USER, INPUT))
                            .code());
        }
    }

    @Test
    void details_requireAnExactConfirmedOptionAndDoNotGuessItsPrice() {
        Map<String, Object> required = product();
        required.put("optionsRequired", true);
        required.put("selectionStatus", "REQUIRED");
        required.put("offer", change((Map<String, Object>) required.get("offer"), "price", null));
        Map<String, Object> variant = Map.of("optionId", "v1", "label", "확인된 옵션", "price", 20000L,
                "availability", "OUT_OF_STOCK", "purchaseAllowed", false);
        required.put("variants", List.of(variant));
        assertEquals("REQUIRED", ((Map<?, ?>) service(response(INPUT, required)).get(USER, INPUT)
                .get("product")).get("selectionStatus"));
        Map<String, Object> selectedInput = change(INPUT, "optionId", "v1");
        Map<String, Object> selected = change(change(required, "selectionStatus", "VALID"), "selectedOptionId", "v1");
        selected.put("offer", change(change((Map<String, Object>) required.get("offer"), "price", 20000L),
                "availability", "OUT_OF_STOCK"));
        assertEquals("VALID", ((Map<?, ?>) service(response(selectedInput, selected)).get(USER, selectedInput)
                .get("product")).get("selectionStatus"));
        selected.put("offer", change((Map<String, Object>) selected.get("offer"), "price", 1L));
        assertThrows(CoreClientException.class,
                () -> service(response(selectedInput, selected)).get(USER, selectedInput));
        Map<String, Object> invalidInput = change(INPUT, "optionId", "wrong");
        Map<String, Object> invalid = change(required, "selectionStatus", "INVALID");
        assertEquals("INVALID", ((Map<?, ?>) service(response(invalidInput, invalid)).get(USER, invalidInput)
                .get("product")).get("selectionStatus"));
    }

    @Test
    void details_outOfStockDiscontinuedAndUnknownAreDistinct() {
        for (String availability : List.of("OUT_OF_STOCK", "DISCONTINUED", "UNKNOWN")) {
            Map<String, Object> product = product();
            product.put("offer", change((Map<String, Object>) product.get("offer"), "availability", availability));
            Map<?, ?> result = (Map<?, ?>) service(response(INPUT, product)).get(USER, INPUT).get("product");
            assertEquals(availability, ((Map<?, ?>) result.get("offer")).get("availability"));
        }
    }

    private ProductDetailService service(Map<String, Object> data) {
        return service((user, request) -> envelope("user-a", "connection-a", data));
    }

    private ProductDetailService service(CoreProductDetails core) {
        return new ProductDetailService(core,
                new ProductCatalogService((user, query) -> Map.of(), McpJsonDefaults.getSchemaValidator(),
                        McpJsonDefaults.getMapper()), McpJsonDefaults.getSchemaValidator());
    }
}
