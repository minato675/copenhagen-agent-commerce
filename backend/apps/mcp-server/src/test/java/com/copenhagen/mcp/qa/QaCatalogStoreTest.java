package com.copenhagen.mcp.qa;

import com.copenhagen.mcp.catalog.ProductDetailRequest;
import com.copenhagen.mcp.catalog.ProductSearchRequest;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QaCatalogStoreTest {

    @TempDir
    Path directory;

    @Test
    void searchAndDetail_oldCatalogDisplayNames_updatesNamesWithoutChangingSource() {
        Map<String, Object> product = QaCoreServerTest.product("shoes", "footwear", 10000);
        QaCatalogStore catalog = new QaCatalogStore(List.of(product), Set.of());
        Map<String, Object> result = catalog.search(ProductSearchRequest.parse(Map.of(),
                McpJsonDefaults.getSchemaValidator()));
        List<Map<String, Object>> offers = QaCatalogStore.maps(
                QaCatalogStore.maps(result.get("products")).getFirst().get("offers"));
        assertEquals("qa-a", offers.getFirst().get("merchantId"));
        assertEquals("노르드 마켓", offers.getFirst().get("merchantName"));
        assertEquals("하운 스토어", offers.get(1).get("merchantName"));
        assertEquals("벨루아 셀렉트", QaCatalogStore.maps(result.get("merchants")).get(2).get("name"));
        Map<?, ?> detail = (Map<?, ?>) catalog.detail(new ProductDetailRequest("shoes", "qa-a", ""))
                .get("product");
        assertEquals("노르드 마켓", ((Map<?, ?>) detail.get("offer")).get("merchantName"));
        assertEquals("가상몰 A", QaCatalogStore.maps(product.get("offers")).getFirst().get("merchantName"));
        assertEquals("Unchanged", QaCatalogStore.merchantName("other", "Unchanged"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUT_OF_STOCK", "DISCONTINUED"})
    void detailAndCart_unavailableMerchant_cannotBecomePurchasableBySelectingInStockVariant(String state)
            throws Exception {
        Map<String, Object> product = new LinkedHashMap<>(QaCoreServerTest.product("product-a", "shoes", 10000));
        product.put("offers", List.of(Map.of("merchantId", "qa-a", "merchantName", "노르드 마켓", "price", 10000,
                "currency", "KRW", "availability", state)));
        product.put("variants", List.of(Map.of("optionId", "option-a", "label", "Blue 250mm", "prices",
                Map.of("qa-a", 10000), "availability", "IN_STOCK", "purchaseAllowed", true)));
        product.put("optionsRequired", true);
        QaCatalogStore catalog = new QaCatalogStore(List.of(product), Set.of());
        Map<?, ?> selected = (Map<?, ?>) catalog.detail(new ProductDetailRequest("product-a", "qa-a", "option-a"))
                .get("product");
        assertEquals(state, ((Map<?, ?>) selected.get("offer")).get("availability"));
        assertEquals(state, QaCatalogStore.maps(selected.get("variants")).getFirst().get("availability"));
        try (QaCartStore store = new QaCartStore(catalog, directory.resolve("cart-" + state + ".json"))) {
            assertEquals("UNAVAILABLE", store.add("user-a", QaCartStoreTest.request(store.get("user-a"),
                    "inventory-request-01")).get("reason"));
            assertEquals(List.of(), store.get("user-a").get("lines"));
        }
        assertEquals("IN_STOCK", QaCatalogStore.maps(product.get("variants")).getFirst().get("availability"));
    }
}
