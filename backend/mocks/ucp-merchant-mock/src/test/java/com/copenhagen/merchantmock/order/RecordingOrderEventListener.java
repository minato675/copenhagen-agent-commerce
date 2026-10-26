package com.copenhagen.merchantmock.order;

import java.util.ArrayList;
import java.util.List;

/** 주문 사건을 기억만 하는 리스너. 웹훅 쪽은 자기 테스트가 본다. */
public final class RecordingOrderEventListener implements OrderEventListener {

    public final List<Order> created = new ArrayList<>();
    public final List<Order> updated = new ArrayList<>();

    @Override
    public void orderCreated(Order order) {
        created.add(order);
    }

    @Override
    public void orderUpdated(Order order) {
        updated.add(order);
    }
}
