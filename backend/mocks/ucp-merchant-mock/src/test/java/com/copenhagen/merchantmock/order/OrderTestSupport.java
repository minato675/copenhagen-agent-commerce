package com.copenhagen.merchantmock.order;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.copenhagen.merchantmock.catalog.CatalogFixture;
import com.copenhagen.merchantmock.catalog.CatalogStore;
import com.copenhagen.merchantmock.checkout.CheckoutSession;
import com.copenhagen.merchantmock.checkout.CheckoutStatus;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 주문 테스트가 같이 쓰는 조립 코드와 체크아웃 샘플.
 *
 * <p>체크아웃을 {@code CheckoutService} 로 만들지 않고 손으로 짠다. 그쪽 조립 코드가 패키지 안에 있기도
 * 하고, 주문 생성이 읽는 것은 저장된 세 값({@code canonical_checkout}, {@code line_skus_json},
 * {@code fulfillment_address})뿐이라 그 셋을 직접 고정하는 편이 무엇을 시험하는지 분명하다.
 */
final class OrderTestSupport {

    /** 2026-09-15 13:00:00 KST. */
    static final Instant NOW = Instant.parse("2026-09-15T04:00:00Z");
    static final String EXPECTED_UPDATED_AT = "2026-09-15T13:00:00+09:00";

    static final String CHECKOUT_ID = "11111111-1111-4111-8111-111111111111";

    private static final String ADDRESS = """
            {"recipient":"홍길동","postal_code":"06236",\
            "address_line1":"서울특별시 강남구 테헤란로 1","address_line2":"101호"}""";

    private OrderTestSupport() {
    }

    record Harness(OrderService service, InMemoryOrderRepository repository, ObjectMapper objectMapper,
            RecordingOrderEventListener events) {
    }

    static Harness harness() {
        CatalogStore catalog = new CatalogStore(CatalogFixture::seed);
        catalog.load();
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        ObjectMapper objectMapper = JsonMapper.builder().build();
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        RecordingOrderEventListener events = new RecordingOrderEventListener();
        OrderService service = new OrderService(
                repository, catalog, objectMapper, MerchantPropertiesFixture.defaults(), clock, events);
        return new Harness(service, repository, objectMapper, events);
    }

    /** 러닝화 270 한 개를 서울로. 92,000원(상품 89,000 + 배송 3,000). 옵션이 저장돼 있다. */
    static CheckoutSession readyCheckout() {
        return checkout(CHECKOUT_ID, canonical(CHECKOUT_ID, "product-101", "러닝화 A", 89000, 1),
                "{\"line-1\":\"product-101-270\"}");
    }

    /** 옵션이 하나뿐인 상품. Core 가 option_value_ids 를 생략할 수 있어 SKU 가 저장되지 않는다. */
    static CheckoutSession singleVariantCheckoutWithoutSku() {
        return checkout(CHECKOUT_ID, canonical(CHECKOUT_ID, "product-104", "보온 텀블러 500ml", 27000, 2), null);
    }

    /** 옵션이 여럿인데 SKU 가 없다. V6 이전 행이나 버그로만 생기는 상태다. */
    static CheckoutSession multiVariantCheckoutWithoutSku() {
        return checkout(CHECKOUT_ID, canonical(CHECKOUT_ID, "product-101", "러닝화 A", 89000, 1), null);
    }

    static CheckoutSession checkout(String id, String canonicalCheckout, String lineSkusJson) {
        return new CheckoutSession(
                id,
                CheckoutStatus.READY_FOR_COMPLETE,
                "KRW",
                92000L,
                ADDRESS,
                null,
                lineSkusJson,
                canonicalCheckout,
                null,
                0L);
    }

    private static String canonical(String id, String productId, String title, long price, int quantity) {
        return """
                {"id":"%s","status":"ready_for_complete","currency":"KRW",\
                "line_items":[{"id":"line-1","item":{"id":"%s","title":"%s","price":%d},"quantity":%d}],\
                "fulfillment":{"type":"shipping","status":"pending","amount":3000},\
                "totals":[{"type":"subtotal","amount":%d},{"type":"fulfillment","amount":3000},\
                {"type":"total","amount":%d}]}"""
                .formatted(id, productId, title, price, quantity,
                        price * quantity, price * quantity + 3000);
    }
}
