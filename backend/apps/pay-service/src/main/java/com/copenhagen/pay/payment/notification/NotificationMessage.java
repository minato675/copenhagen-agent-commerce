package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.payment.policy.PaymentStatus;

public record NotificationMessage(
        String eventId,
        NotificationEventType type,
        String approvalId,
        String paymentId,
        String status,
        String nextAction,
        String purchaseRequestId) {

    public NotificationMessage(String eventId, NotificationEventType type, String approvalId,
            String paymentId, String status, String nextAction) {
        this(eventId, type, approvalId, paymentId, status, nextAction, null);
    }

    public String pushId() {
        return eventId;
    }

    static String nextAction(PaymentStatus status) {
        return status == PaymentStatus.UNKNOWN
                ? "WAIT_FOR_PAYMENT_STATUS"
                : "SHOW_PAYMENT_RESULT";
    }

    @Override
    public String toString() {
        return "NotificationMessage["
                + "eventId=<redacted>"
                + ", type=" + type
                + ", approvalId=<redacted>"
                + ", paymentId=<redacted>"
                + ", status=" + status
                + ", nextAction=" + nextAction
                + ']';
    }
}
