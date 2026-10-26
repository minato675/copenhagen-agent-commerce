package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.catalog.ProductCatalogService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaCoreServerTest {

    private static final McpIdentity IDENTITY = new McpIdentity("qa-user", "qa-connection", Set.of(),
            Instant.now().plusSeconds(600));

    static Map<String, Object> product(String id, String category, long price) {
        return Map.of("productId", id, "name", "QA " + id, "brand", "fixture", "category", category,
                "imageUrl", "", "offers", List.of(
                        Map.of("merchantId", "qa-a", "merchantName", "가상몰 A", "price", price,
                                "currency", "KRW", "availability", "UNKNOWN"),
                        Map.of("merchantId", "qa-b", "merchantName", "가상몰 B", "price", price + 10,
                                "currency", "KRW", "availability", "OUT_OF_STOCK")));
    }

    @Test
    void http_boundaryHandlesAllCategoriesPagingFiltersAndPartialFailure() throws Exception {
        List<Map<String, Object>> products = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            products.add(product("p" + index, "group-a", index * 100L));
        }
        products.add(product("other", "group-b", 0));
        try (QaCoreServer core = new QaCoreServer(new QaCatalogStore(products, Set.of()))) {
            ProductCatalogService service = service(core);
            Map<String, Object> all = service.search(IDENTITY, Map.of());
            assertEquals("OK", all.get("status"));
            assertEquals(10, ((Number) all.get("total")).intValue());
            assertEquals(List.of("group-a", "group-b"), all.get("categories"));
            assertEquals(4, QaCatalogStore.maps(all.get("products")).size());
            assertEquals(true, all.get("hasNext"));
            assertEquals(all.get("products"), service.search(IDENTITY, Map.of("category", ""))
                    .get("products"));
            Map<String, Object> last = service.search(IDENTITY, Map.of("query", "QA", "page", 3));
            assertEquals("OK", last.get("status"));
            assertEquals(10, ((Number) last.get("total")).intValue());
            assertEquals(List.of("group-a", "group-b"), QaCatalogStore.maps(last.get("products")).stream()
                    .map(product -> product.get("category")).toList());
            assertEquals(false, last.get("hasNext"));
            assertEquals("EMPTY", service.search(IDENTITY, Map.of("page", 4)).get("status"));
            Map<String, Object> page = service.search(IDENTITY, Map.of("category", "group-a", "page", 2));
            assertEquals("p5", QaCatalogStore.maps(page.get("products")).getFirst().get("productId"));
            assertEquals(true, page.get("hasNext"));
            assertEquals("EMPTY", service.search(IDENTITY, Map.of("category", "group-a", "page", 4)).get("status"));
            Map<String, Object> filtered = service.search(IDENTITY, Map.of("category", "group-a",
                    "merchantId", "qa-b", "minPrice", 210, "maxPrice", 410, "sort", "PRICE_DESC"));
            assertEquals(3, ((Number) filtered.get("total")).intValue());
            assertEquals("p4", QaCatalogStore.maps(filtered.get("products")).getFirst().get("productId"));
            assertEquals("EMPTY", service.search(IDENTITY, Map.of("query", "absent")).get("status"));
            assertEquals(9, ((Number) service.search(IDENTITY, Map.of("category", "group-a")).get("total")).intValue());
        }
        try (QaCoreServer core = new QaCoreServer(new QaCatalogStore(products, Set.of("qa-b")))) {
            Map<String, Object> allPartial = service(core).search(IDENTITY, Map.of());
            assertEquals("PARTIAL", allPartial.get("status"));
            assertEquals(10, ((Number) allPartial.get("total")).intValue());
            Map<String, Object> partial = service(core).search(IDENTITY, Map.of("category", "group-a"));
            assertEquals("PARTIAL", partial.get("status"));
            assertEquals(List.of("qa-b"), partial.get("failedMerchants"));
            assertTrue(QaCatalogStore.maps(partial.get("products")).stream()
                    .allMatch(product -> QaCatalogStore.maps(product.get("offers")).size() == 1));
        }
    }

    private ProductCatalogService service(QaCoreServer core) {
        return new ProductCatalogService(core.adapter(), McpJsonDefaults.getSchemaValidator(),
                McpJsonDefaults.getMapper());
    }
}
