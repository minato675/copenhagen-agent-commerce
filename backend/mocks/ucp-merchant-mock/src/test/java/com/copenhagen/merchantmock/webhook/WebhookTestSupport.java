package com.copenhagen.merchantmock.webhook;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.copenhagen.merchantmock.order.Order;
import com.copenhagen.merchantmock.order.OrderStatus;

/** 웹훅 테스트가 같이 쓰는 샘플. */
final class WebhookTestSupport {

    /** 2026-09-16 13:00:00 KST. */
    static final Instant NOW = Instant.parse("2026-09-16T04:00:00Z");
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final OffsetDateTime NOW_SEOUL = OffsetDateTime.ofInstant(NOW, SEOUL);
    static final String EXPECTED_TIMESTAMP = "2026-09-16T13:00:00+09:00";

    static final String ORDER_ID = "order-1";
    static final String CHECKOUT_ID = "11111111-1111-4111-8111-111111111111";

    /** 주문 조회 응답과 같은 바이트. 웹훅 페이로드 안쪽 객체의 원본이다. */
    static final String RAW_ORDER_JSON = """
            {"id":"order-1","checkout_id":"11111111-1111-4111-8111-111111111111","status":"CREATED",\
            "fulfillment":{"status":"CREATED"},"updated_at":"2026-09-16T13:00:00+09:00"}""";

    private WebhookTestSupport() {
    }

    static Order order() {
        return new Order(ORDER_ID, "merchant-a", CHECKOUT_ID, OrderStatus.CREATED, 92000L, 3000L, "KRW",
                RAW_ORDER_JSON,
                "홍길동", null, "06236", "서울특별시 강남구 테헤란로 1", "101호", 0L, NOW_SEOUL);
    }

    /** {@code merchant.webhook.retry-initial-delay} 기본값. 새 행의 next_attempt_at 이 이만큼 밀린다. */
    static final Duration FIRST_DELAY = Duration.ofSeconds(10);

    static WebhookDelivery pendingCreated(String webhookId) {
        return WebhookDelivery.pending(webhookId, ORDER_ID, WebhookEventType.ORDER_CREATED,
                "{\"order\":" + RAW_ORDER_JSON + "}", NOW_SEOUL, FIRST_DELAY);
    }
}
