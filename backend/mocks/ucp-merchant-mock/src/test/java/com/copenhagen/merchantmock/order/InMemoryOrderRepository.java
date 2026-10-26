package com.copenhagen.merchantmock.order;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB 없이 돌리는 주문 저장소.
 *
 * <p>{@code checkout_session_id} UNIQUE 를 JDBC 구현과 같게 흉내 낸다. 그 제약이 중복 주문의 최종
 * 방어선이라(DESIGN.md 불변 조건 3번) 여기서도 같은 자리에서 막혀야 테스트가 의미를 가진다.
 */
public final class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final List<OrderItem> items = new ArrayList<>();

    /** true 면 다음 insert 한 번이 UNIQUE 위반처럼 실패한다. 경합 경로를 만드는 데 쓴다. */
    private boolean failNextInsert;

    @Override
    public synchronized boolean insert(Order order, List<OrderItem> newItems) {
        if (failNextInsert) {
            failNextInsert = false;
            return false;
        }
        boolean duplicate = orders.values().stream()
                .anyMatch(existing -> existing.checkoutSessionId().equals(order.checkoutSessionId()));
        if (duplicate || orders.containsKey(order.id())) {
            return false;
        }
        orders.put(order.id(), order);
        items.addAll(newItems);
        return true;
    }

    @Override
    public Optional<Order> findById(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    @Override
    public synchronized Optional<Order> findByCheckoutSessionId(String checkoutSessionId) {
        return orders.values().stream()
                .filter(order -> order.checkoutSessionId().equals(checkoutSessionId))
                .findFirst();
    }

    @Override
    public synchronized List<OrderItem> findItems(String orderId) {
        return items.stream()
                .filter(item -> item.orderId().equals(orderId))
                .sorted(Comparator.comparingInt(OrderItem::lineNo))
                .toList();
    }

    /** 다음 INSERT 를 한 번 실패시킨다. 다른 요청이 먼저 주문을 만든 상황을 만든다. */
    synchronized void failNextInsert() {
        this.failNextInsert = true;
    }

    /** 주문을 직접 밀어 넣는다. 경합에서 이긴 쪽을 미리 앉혀 둘 때 쓴다. */
    synchronized void put(Order order) {
        orders.put(order.id(), order);
    }

    synchronized int size() {
        return orders.size();
    }
}
