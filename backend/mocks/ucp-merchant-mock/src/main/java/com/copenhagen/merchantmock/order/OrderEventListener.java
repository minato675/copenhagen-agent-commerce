package com.copenhagen.merchantmock.order;

/**
 * 주문 사건을 듣는 쪽. 구현은 {@code webhook/OrderWebhookPublisher} 다(DESIGN.md 8.6절).
 *
 * <p>인터페이스를 order 패키지에 두는 이유는 순환 의존을 끊기 위해서다. webhook 은 주문을 알아야 하지만
 * 주문은 웹훅을 몰라야 한다. {@link OrderService#create} 가 주문 INSERT 와 같은 트랜잭션 안에서 부르므로
 * 구현은 여기서 행을 남기기만 하고 바깥 I/O 는 커밋 뒤로 미룬다.
 */
public interface OrderEventListener {

    void orderCreated(Order order);

    void orderUpdated(Order order);
}
