package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.policy.PaymentStatus;

public interface NotificationEventPublisher {

    void publishApprovalRequested(AuthorizationSession session);

    void publishPaymentResult(
            String paymentId,
            String authorizationSessionId,
            PaymentStatus status);

    static NotificationEventPublisher noop() {
        return new NotificationEventPublisher() {
            @Override
            public void publishApprovalRequested(AuthorizationSession session) {
                // Direct unit tests can exercise the payment flow without an outbox.
            }

            @Override
            public void publishPaymentResult(
                    String paymentId,
                    String authorizationSessionId,
                    PaymentStatus status) {
                // Direct unit tests can exercise the payment flow without an outbox.
            }
        };
    }
}
