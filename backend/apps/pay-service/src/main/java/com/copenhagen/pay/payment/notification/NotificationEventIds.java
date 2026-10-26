package com.copenhagen.pay.payment.notification;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class NotificationEventIds {

    private NotificationEventIds() {
    }

    static String approvalRequested(String approvalId) {
        return UUID.nameUUIDFromBytes(
                ("APPROVAL_REQUESTED:" + approvalId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    static String paymentResult(String paymentId, String status) {
        return UUID.nameUUIDFromBytes(
                ("PAYMENT_RESULT:" + paymentId + ':' + status).getBytes(StandardCharsets.UTF_8))
                .toString();
    }
}
