package com.copenhagen.pay.common.idempotency;

import java.time.Instant;

public record IdempotencyRecord(
        String id,
        IdempotencyScope scope,
        String requestHash,
        Status status,
        Integer responseStatus,
        String responseContentType,
        String responseBodyEncrypted,
        Instant createdAt,
        Instant completedAt) {

    public enum Status {
        PROCESSING,
        COMPLETED
    }
}
