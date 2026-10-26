package com.copenhagen.pay.payment.notification;

public enum NotificationOutboxStatus {
    PENDING,
    RUNNING,
    SENT,
    FAILED,
    EXHAUSTED
}
