package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationOutboxRepository {

    void insertApprovalRequested(String approvalId, String userId, Instant now);

    void insertPaymentResult(
            String paymentId,
            String approvalId,
            PaymentStatus status,
            Instant now);

    Optional<NotificationOutboxEvent> claimNext(Instant now, Instant leaseExpiresAt);

    void ensureDeliveries(String eventId, List<String> fids, Instant nextRunAt);

    void pruneInactiveDeliveries(String eventId);

    Optional<NotificationDelivery> claimNextDelivery(
            String eventId,
            Instant now,
            Instant leaseExpiresAt);

    boolean markDeliverySent(NotificationDelivery delivery, Instant sentAt);

    boolean markDeliveryFailed(
            NotificationDelivery delivery,
            Instant nextRunAt,
            String errorCode,
            boolean exhausted);

    DeliverySummary summarizeDeliveries(String eventId);

    boolean markEventSent(NotificationOutboxEvent event, Instant sentAt);

    boolean markEventRetry(
            NotificationOutboxEvent event,
            Instant nextRunAt,
            String errorCode);

    record DeliverySummary(
            boolean hasRetryableDeliveries,
            boolean hasExhaustedDeliveries,
            Instant nextRunAt) {
    }
}
