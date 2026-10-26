package com.copenhagen.merchantmock.webhook;

/** 웹훅 이벤트 종류. DB CHECK 와 Core 확정본 5.2 의 값이다(DESIGN.md 7.3절). */
public enum WebhookEventType {
    ORDER_CREATED("order.created"),
    ORDER_UPDATED("order.updated");

    private final String value;

    WebhookEventType(String value) {
        this.value = value;
    }

    /** DB 와 헤더에 쓰는 소문자 점 표기. */
    public String value() {
        return value;
    }

    public static WebhookEventType fromValue(String value) {
        for (WebhookEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("모르는 웹훅 이벤트 종류: " + value);
    }
}
