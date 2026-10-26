package com.copenhagen.pay.payment.policy;

import java.time.Instant;

public final class ReconciliationJobPolicy {

    private ReconciliationJobPolicy() {
    }

    public static boolean isClaimable(
            ReconciliationJobStatus status,
            Instant nextRunAt,
            Instant leaseExpiresAt,
            Instant now) {
        if (status == null || now == null) {
            return false;
        }
        if (status == ReconciliationJobStatus.PENDING
                || status == ReconciliationJobStatus.FAILED) {
            return nextRunAt != null && !nextRunAt.isAfter(now);
        }
        return status == ReconciliationJobStatus.RUNNING
                && leaseExpiresAt != null
                && !leaseExpiresAt.isAfter(now);
    }

    public static boolean isExhausted(int attemptCount, int maxAttempts) {
        if (attemptCount < 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("attemptCount and maxAttempts must be valid");
        }
        return attemptCount >= maxAttempts;
    }
}
