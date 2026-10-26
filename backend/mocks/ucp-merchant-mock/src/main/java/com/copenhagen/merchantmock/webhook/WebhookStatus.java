package com.copenhagen.merchantmock.webhook;

/** 발송 상태(DESIGN.md 7.4절). {@code FAILED} 는 184 재전송 워커가 횟수를 넘겼을 때만 만든다. */
public enum WebhookStatus {
    PENDING,
    DELIVERED,
    FAILED
}
