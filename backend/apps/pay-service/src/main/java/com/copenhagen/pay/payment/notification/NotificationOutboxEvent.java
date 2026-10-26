package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Instant;

public record NotificationOutboxEvent(
        String id,
        String eventId,
        NotificationEventType eventType,
        String userId,
        String requestId,
        String approvalId,
        String paymentId,
        PaymentStatus paymentStatus,
        String payloadJson,
        NotificationOutboxStatus status,
        int attemptCount,
        Instant nextRunAt,
        Instant leaseExpiresAt,
        Instant sentAt,
        String lastError,
        Instant createdAt) {

    public NotificationOutboxEvent(
            String id,
            String eventId,
            NotificationEventType eventType,
            String userId,
            String approvalId,
            String paymentId,
            PaymentStatus paymentStatus,
            String payloadJson,
            NotificationOutboxStatus status,
            int attemptCount,
            Instant nextRunAt,
            Instant leaseExpiresAt,
            Instant sentAt,
            String lastError,
            Instant createdAt) {
        this(
                id,
                eventId,
                eventType,
                userId,
                null,
                approvalId,
                paymentId,
                paymentStatus,
                payloadJson,
                status,
                attemptCount,
                nextRunAt,
                leaseExpiresAt,
                sentAt,
                lastError,
                createdAt);
    }

    @Override
    public String toString() {
        return "NotificationOutboxEvent["
                + "id=<redacted>"
                + ", eventId=<redacted>"
                + ", eventType=" + eventType
                + ", userId=<redacted>"
                + ", requestId=" + requestId
                + ", approvalId=<redacted>"
                + ", paymentId=<redacted>"
                + ", paymentStatus=" + paymentStatus
                + ", payload=<redacted>"
                + ", status=" + status
                + ", attemptCount=" + attemptCount
                + ", nextRunAt=" + nextRunAt
                + ", leaseExpiresAt=" + leaseExpiresAt
                + ", sentAt=" + sentAt
                + ", lastError=<redacted>"
                + ", createdAt=" + createdAt
                + ']';
    }
}
