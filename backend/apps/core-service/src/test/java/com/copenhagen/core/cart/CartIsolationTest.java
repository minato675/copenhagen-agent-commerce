package com.copenhagen.core.cart;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.product.CartProductSnapshot;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.product.ProductService;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CartIsolationTest {

    private final FakeCartRepository repository = new FakeCartRepository();
    private final CartService service = new CartService(repository, mock(ProductService.class),
            new CartMerchants(new MerchantsProperties(List.of(merchant("merchant-a", 1L), merchant("merchant-b", 2L)))),
            new ObjectMapper(), Clock.systemUTC());

    @Test
    void sameProductAndSkuInDifferentMerchants_keepsPricesAndQuantitiesSeparateDuringAddAndReplace() {
        service.add(1L, 0, "merchant-a", snapshot("daiso:1001712", "default", 5030L), 1, 99);
        CartAddResult second = service.add(1L, 1, "merchant-b", snapshot("daiso:1001712", "default", 4520L), 1, 99);
        service.add(1L, 2, "merchant-a", snapshot("daiso:1001712", "default", 5030L), 2, 99);
        service.replaceVariant(1L, second.cartItemId(), 3, snapshot("daiso:1001712", "default", 4520L), 4, 99);
        List<CartItem> items = repository.findItemsByCartId(1L);
        assertEquals(2, items.size());
        assertEquals(1L, items.get(0).merchantId());
        assertEquals(3, items.get(0).quantity());
        assertEquals(5030L, items.get(0).unitPriceAmount());
        assertEquals(2L, items.get(1).merchantId());
        assertEquals(4, items.get(1).quantity());
        assertEquals(4520L, items.get(1).unitPriceAmount());
    }

    @Test
    void hundredLines_allowMergingButRejectAnAdditionalLine() {
        for (int index = 0; index < 100; index++) {
            service.add(1L, index, "merchant-a", snapshot("product-" + index, "default", 100L), 1, 99);
        }
        service.add(1L, 100, "merchant-a", snapshot("product-0", "default", 100L), 1, 99);
        CoreException failure = assertThrows(CoreException.class, () -> service.add(
                1L, 101, "merchant-a", snapshot("product-100", "default", 100L), 1, 99));
        assertEquals(CoreErrorCode.CART_ITEM_LIMIT_EXCEEDED, failure.errorCode());
        assertEquals(100, repository.findItemsByCartId(1L).size());
        assertEquals(2, repository.findItemsByCartId(1L).get(0).quantity());
    }

    private static CartProductSnapshot snapshot(String product, String sku, long price) {
        return new CartProductSnapshot(product, sku, "상품", "https://merchant.example/product", null, price,
                "KRW", List.of(new CartProductSnapshot.SelectedOption(sku, "종류", "기본")));
    }

    private static MerchantsProperties.MerchantConfig merchant(String id, long ref) {
        return new MerchantsProperties.MerchantConfig(id, ref, id, URI.create("http://merchant:8080"),
                Duration.ofSeconds(1), Duration.ofSeconds(3));
    }
}
