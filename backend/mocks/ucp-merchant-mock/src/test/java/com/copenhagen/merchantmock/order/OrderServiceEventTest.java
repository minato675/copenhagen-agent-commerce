package com.copenhagen.merchantmock.order;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.order.OrderMessages.Created;
import com.copenhagen.merchantmock.order.OrderTestSupport.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.copenhagen.merchantmock.order.OrderTestSupport.readyCheckout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주문 생성이 웹훅 쪽에 사건을 내는지(Jira 181, DESIGN.md 8.6절). 새 주문 하나에 사건 하나다.
 */
class OrderServiceEventTest {

    @Test
    @DisplayName("새 주문이 생기면 orderCreated 가 한 번 불리고 그 주문이 실린다")
    void create_newOrder_emitsOrderCreatedOnce() {
        // given
        Harness harness = OrderTestSupport.harness();

        // when
        Created created = harness.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // then
        assertEquals(1, harness.events().created.size());
        Order emitted = harness.events().created.get(0);
        assertEquals(created.id(), emitted.id());
        assertEquals(OrderStatus.CREATED, emitted.status());
        assertTrue(emitted.rawOrderJson().contains("\"checkout_id\""), "웹훅 페이로드의 원본이 실려야 한다");
        assertTrue(harness.events().updated.isEmpty());
    }

    @Test
    @DisplayName("같은 체크아웃으로 다시 만들면 기존 주문을 돌려주고 사건은 다시 내지 않는다")
    void create_duplicateCheckout_doesNotEmitAgain() {
        // given
        Harness harness = OrderTestSupport.harness();
        harness.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // when
        harness.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // then
        assertEquals(1, harness.events().created.size(), "재시도는 새 주문이 아니라 사건도 없다");
    }

    @Test
    @DisplayName("INSERT 경합에서 진 쪽도 사건을 내지 않는다")
    void create_lostInsertRace_doesNotEmit() {
        // given
        Harness harness = OrderTestSupport.harness();
        harness.service().create(readyCheckout(), PaymentStatus.SUCCESS);
        harness.events().created.clear();
        harness.repository().failNextInsert();

        // when
        harness.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // then
        assertTrue(harness.events().created.isEmpty());
    }

    @Test
    @DisplayName("결제가 성공이 아니면 주문도 사건도 없다")
    void create_paymentNotSuccess_emitsNothing() {
        // given
        Harness harness = OrderTestSupport.harness();

        // when & then
        assertThrows(IllegalStateException.class,
                () -> harness.service().create(readyCheckout(), PaymentStatus.UNKNOWN));
        assertTrue(harness.events().created.isEmpty());
    }
}
