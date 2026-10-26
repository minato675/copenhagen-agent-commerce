package com.copenhagen.merchantmock.order;

import java.time.OffsetDateTime;
import java.util.List;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.checkout.CheckoutSession;
import com.copenhagen.merchantmock.order.OrderMessages.Created;
import com.copenhagen.merchantmock.order.OrderMessages.OrderView;
import com.copenhagen.merchantmock.order.OrderTestSupport.Harness;
import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.copenhagen.merchantmock.order.OrderTestSupport.CHECKOUT_ID;
import static com.copenhagen.merchantmock.order.OrderTestSupport.EXPECTED_UPDATED_AT;
import static com.copenhagen.merchantmock.order.OrderTestSupport.harness;
import static com.copenhagen.merchantmock.order.OrderTestSupport.multiVariantCheckoutWithoutSku;
import static com.copenhagen.merchantmock.order.OrderTestSupport.readyCheckout;
import static com.copenhagen.merchantmock.order.OrderTestSupport.singleVariantCheckoutWithoutSku;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주문 생성과 조회(Jira 180).
 *
 * <p>여기서 지키는 것은 둘이다. 주문은 결제 성공에서만 생긴다는 것과, 한 체크아웃에 주문이 하나라는 것.
 * 둘 다 DESIGN.md 2절 불변 조건이다.
 */
class OrderServiceTest {

    @Test
    @DisplayName("결제가 성공이면 주문과 주문 항목이 생긴다")
    void create_paymentSuccess_savesOrderWithItems() {
        // given
        Harness h = harness();

        // when
        Created created = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // then
        Order order = h.repository().findById(created.id()).orElseThrow();
        assertEquals(CHECKOUT_ID, order.checkoutSessionId());
        assertEquals(OrderStatus.CREATED, order.status());
        assertEquals(92000L, order.totalAmount());
        assertEquals(3000L, order.shippingFeeAmount(), "스냅샷의 fulfillment 줄을 그대로 적는다(427)");
        assertEquals("KRW", order.currency());

        List<OrderItem> items = h.repository().findItems(created.id());
        assertEquals(1, items.size());
        assertEquals(1, items.get(0).lineNo());
        assertEquals("러닝화 A", items.get(0).title());
        assertEquals(89000L, items.get(0).unitAmount());
        assertEquals(1, items.get(0).quantity());
        assertEquals(89000L, items.get(0).lineAmount());
    }

    @Test
    @DisplayName("주문 항목에 선택된 SKU 와 상품 FK 가 들어간다")
    void create_paymentSuccess_copiesSelectedSkuAndProductId() {
        // given
        Harness h = harness();

        // when
        Created created = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // then
        OrderItem item = h.repository().findItems(created.id()).get(0);
        assertEquals("product-101-270", item.sku(), "체크아웃이 남긴 line_skus_json 에서 와야 한다");
        assertEquals("product-101-uuid", item.productId(), "외부 코드가 아니라 products.id 여야 한다");
    }

    @Test
    @DisplayName("주문에 주문 시점 배송지 사본이 들어간다")
    void create_paymentSuccess_copiesShippingSnapshot() {
        // given
        Harness h = harness();

        // when
        Created created = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // then
        Order order = h.repository().findById(created.id()).orElseThrow();
        assertEquals("홍길동", order.recipientName());
        assertEquals("06236", order.postalCode());
        assertEquals("서울특별시 강남구 테헤란로 1", order.addressLine1());
        assertEquals("101호", order.addressLine2());
        assertNull(order.recipientPhone(), "Core 가 연락처를 보내지 않아 비어 있어야 한다");
    }

    @Test
    @DisplayName("같은 체크아웃으로 두 번 만들어도 주문은 하나다")
    void create_calledTwice_returnsSameOrder() {
        // given
        Harness h = harness();
        Created first = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // when
        Created second = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // then
        assertEquals(first.id(), second.id());
        assertEquals(1, h.repository().size());
    }

    @Test
    @DisplayName("INSERT 가 UNIQUE 에 걸리면 먼저 만들어진 주문을 돌려준다")
    void create_insertRejected_returnsExistingOrder() {
        // given
        Harness h = harness();
        Created winner = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);
        h.repository().failNextInsert();

        // when
        CheckoutSession sameCheckout = readyCheckout();
        Created loser = h.service().create(sameCheckout, PaymentStatus.SUCCESS);

        // then
        assertEquals(winner.id(), loser.id());
        assertEquals(1, h.repository().size());
    }

    @Test
    @DisplayName("결제가 FAILED 면 주문을 만들지 않는다")
    void create_paymentFailed_createsNothing() {
        // given
        Harness h = harness();

        // when
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> h.service().create(readyCheckout(), PaymentStatus.FAILED));

        // then
        assertTrue(thrown.getMessage().contains("FAILED"));
        assertEquals(0, h.repository().size());
    }

    @Test
    @DisplayName("결제가 UNKNOWN 이면 주문을 만들지 않는다")
    void create_paymentUnknown_createsNothing() {
        // given
        Harness h = harness();

        // when
        assertThrows(IllegalStateException.class,
                () -> h.service().create(readyCheckout(), PaymentStatus.UNKNOWN));

        // then
        assertEquals(0, h.repository().size(), "UNKNOWN 에서 주문이 생기면 이중 결제로 이어진다");
    }

    @Test
    @DisplayName("옵션이 하나뿐인 상품은 SKU 가 저장되지 않았어도 상품에서 되찾는다")
    void create_singleVariantWithoutStoredSku_resolvesFromProduct() {
        // given
        Harness h = harness();

        // when
        Created created = h.service().create(singleVariantCheckoutWithoutSku(), PaymentStatus.SUCCESS);

        // then
        OrderItem item = h.repository().findItems(created.id()).get(0);
        assertEquals("product-104-single", item.sku());
        assertEquals(2, item.quantity());
        assertEquals(54000L, item.lineAmount());
    }

    @Test
    @DisplayName("옵션이 여럿인데 SKU 가 없으면 주문을 만들지 않는다")
    void create_multiVariantWithoutStoredSku_createsNothing() {
        // given
        Harness h = harness();

        // when
        assertThrows(IllegalStateException.class,
                () -> h.service().create(multiVariantCheckoutWithoutSku(), PaymentStatus.SUCCESS));

        // then
        assertEquals(0, h.repository().size(), "무엇을 팔았는지 모르는 주문을 남기지 않는다");
    }

    @Test
    @DisplayName("주문 조회는 다섯 필드를 돌려주고 배송 상태가 주문 상태를 비춘다")
    void get_existingOrder_returnsFiveFieldsWithMirroredFulfillment() {
        // given
        Harness h = harness();
        Created created = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // when
        OrderView view = h.service().get("merchant-a", created.id());

        // then
        assertEquals(created.id(), view.id());
        assertEquals(CHECKOUT_ID, view.checkoutId());
        assertEquals("CREATED", view.status());
        assertEquals("CREATED", view.fulfillment().status());
        assertEquals(EXPECTED_UPDATED_AT, view.updatedAt());
    }

    @Test
    @DisplayName("DB 가 UTC 마이크로초로 돌려줘도 응답은 Asia/Seoul 초 단위다")
    void get_utcTimestampWithMicros_rendersAsSeoulSeconds() {
        // given
        Harness h = harness();
        Created created = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);
        Order stored = h.repository().findById(created.id()).orElseThrow();
        h.repository().put(new Order(stored.id(), stored.merchantId(), stored.checkoutSessionId(), stored.status(),
                stored.totalAmount(), stored.shippingFeeAmount(), stored.currency(), stored.rawOrderJson(),
                stored.recipientName(),
                stored.recipientPhone(), stored.postalCode(), stored.addressLine1(), stored.addressLine2(),
                stored.version(), OffsetDateTime.parse("2026-09-15T07:11:58.900388Z")));

        // when
        OrderView view = h.service().get("merchant-a", created.id());

        // then
        assertEquals("2026-09-15T16:11:58+09:00", view.updatedAt(),
                "timestamptz 는 UTC 마이크로초로 올라온다. 17절이 정한 모양으로 내보내야 한다");
    }

    @Test
    @DisplayName("없는 주문을 조회하면 ORDER_NOT_FOUND 다")
    void get_missingOrder_throwsOrderNotFound() {
        // given
        Harness h = harness();

        // when
        ApiException thrown = assertThrows(ApiException.class, () -> h.service().get("merchant-a", "order-없음"));

        // then
        assertEquals("ORDER_NOT_FOUND", thrown.errorCode());
        assertEquals("CHECK_PURCHASE_STATUS", thrown.nextAction());
    }

    @Test
    @DisplayName("생성 응답의 permalink_url 은 설정된 공개 주소를 쓴다")
    void create_paymentSuccess_buildsPermalinkFromPublicBaseUrl() {
        // given
        Harness h = harness();

        // when
        Created created = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // then
        assertNotNull(created.permalinkUrl());
        assertTrue(created.permalinkUrl().endsWith("/orders/" + created.id()),
                "실제 값: " + created.permalinkUrl());
    }

    @Test
    @DisplayName("raw_order_json 은 생성 시점 스냅샷이다")
    void create_paymentSuccess_storesCreationSnapshot() {
        // given
        Harness h = harness();

        // when
        Created created = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // then
        Order order = h.repository().findById(created.id()).orElseThrow();
        OrderView snapshot = h.objectMapper().readValue(order.rawOrderJson(), OrderView.class);
        assertEquals(created.id(), snapshot.id());
        assertEquals(CHECKOUT_ID, snapshot.checkoutId());
        assertEquals("CREATED", snapshot.status());
        assertEquals(EXPECTED_UPDATED_AT, snapshot.updatedAt());
    }

    @Test
    @DisplayName("주문은 체크아웃의 몰을 물려받고 다른 몰의 경로로는 보이지 않는다 (393)")
    void get_otherMall_isNotFound() {
        // given
        Harness h = harness();
        Created created = h.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // when & then
        assertEquals("merchant-a", h.repository().findById(created.id()).orElseThrow().merchantId());
        assertTrue(created.permalinkUrl().contains("/api/ucp/v1/merchant-a/orders/"), created.permalinkUrl());
        assertEquals(created.id(), h.service().get("merchant-a", created.id()).id());
        ApiException other = assertThrows(ApiException.class, () -> h.service().get("merchant-b", created.id()));
        assertEquals("ORDER_NOT_FOUND", other.errorCode());
    }
}
