package com.copenhagen.contract.ucp;

/** Merchant 주문 알림. wire 필드명을 그대로 써 별도 JSON 구현에 의존하지 않는다. */
public record OrderWebhookPayload(String merchant_id, Order order) {

    public record Order(String id, String checkout_id, String status, Fulfillment fulfillment, String updated_at) {
    }

    public record Fulfillment(String status) {
    }
}
