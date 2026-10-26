package com.copenhagen.pay.payment.notification;

import java.time.Instant;

public record NotificationDelivery(
        String id,
        String eventId,
        String fid,
        int attemptCount,
        Instant leaseExpiresAt) {

    @Override
    public String toString() {
        return "NotificationDelivery["
                + "id=<redacted>"
                + ", eventId=<redacted>"
                + ", fid=<redacted>"
                + ", attemptCount=" + attemptCount
                + ", leaseExpiresAt=" + leaseExpiresAt
                + ']';
    }
}
