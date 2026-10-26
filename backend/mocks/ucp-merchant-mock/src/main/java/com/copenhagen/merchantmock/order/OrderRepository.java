package com.copenhagen.merchantmock.order;

import java.util.List;
import java.util.Optional;

/**
 * 주문 저장 경계. 구현은 {@link JdbcOrderRepository}. 테스트는 인메모리 구현으로 돌린다.
 */
public interface OrderRepository {

    /**
     * 주문과 주문 항목을 함께 넣는다. {@code checkout_session_id} UNIQUE 에 걸리면 false 다.
     *
     * <p>그 UNIQUE 가 중복 주문의 최종 방어선이다(DESIGN.md 불변 조건 3번). 멱등 로직에 버그가 있어도
     * DB 가 막는다. false 를 받은 쪽은 {@link #findByCheckoutSessionId} 로 기존 주문을 돌려준다.
     */
    boolean insert(Order order, List<OrderItem> items);

    Optional<Order> findById(String id);

    Optional<Order> findByCheckoutSessionId(String checkoutSessionId);

    /** 주문 상세. {@code line_no} 순서다. 상품별 판매 집계와 테스트가 읽는다. */
    List<OrderItem> findItems(String orderId);
}
