package com.copenhagen.pay.common.idempotency;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepository {

    Optional<IdempotencyRecord> findByScope(IdempotencyScope scope);

    boolean insert(IdempotencyRecord record);

    boolean complete(
            IdempotencyScope scope,
            int responseStatus,
            String responseContentType,
            String responseBodyEncrypted,
            Instant completedAt);

    boolean deleteProcessing(IdempotencyScope scope);
}
