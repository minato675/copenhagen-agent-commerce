package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JdbcNotificationEventPublisher implements NotificationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(JdbcNotificationEventPublisher.class);

    private final NotificationOutboxRepository repository;
    private final Clock clock;

    public JdbcNotificationEventPublisher(
            NotificationOutboxRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void publishApprovalRequested(AuthorizationSession session) {
        String eventId = NotificationEventIds.approvalRequested(session.id());
        try (PayLogContext.Scope ignored = PayLogContext.open(
                PayLogContext.USER_ID, PayLogContext.fingerprint(session.userId()),
                PayLogContext.APPROVAL_ID, session.id(),
                PayLogContext.EVENT_ID, eventId,
                PayLogContext.PUSH_ID, eventId)) {
            log.info("notification.event.enqueue.start eventType=APPROVAL_REQUESTED");
            repository.insertApprovalRequested(session.id(), session.userId(), clock.instant());
            log.info("notification.event.enqueue.requested eventType=APPROVAL_REQUESTED");
        }
    }

    @Override
    public void publishPaymentResult(
            String paymentId,
            String authorizationSessionId,
            PaymentStatus status) {
        String eventId = NotificationEventIds.paymentResult(paymentId, status.name());
        try (PayLogContext.Scope ignored = PayLogContext.open(
                PayLogContext.APPROVAL_ID, authorizationSessionId,
                PayLogContext.PAYMENT_ID, paymentId,
                PayLogContext.EVENT_ID, eventId,
                PayLogContext.PUSH_ID, eventId)) {
            log.info(
                    "notification.event.enqueue.start eventType=PAYMENT_RESULT status={}",
                    status);
            repository.insertPaymentResult(
                    paymentId,
                    authorizationSessionId,
                    status,
                    clock.instant());
            log.info(
                    "notification.event.enqueue.requested eventType=PAYMENT_RESULT status={}",
                    status);
        }
    }
}
