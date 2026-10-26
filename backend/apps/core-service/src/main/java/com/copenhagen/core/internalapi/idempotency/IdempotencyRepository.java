package com.copenhagen.core.internalapi.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {

    /** 만료된 행은 없는 것으로 본다. */
    Optional<IdempotencyRecord> find(long userId, UUID idempotencyKey, Instant now);

    /** 만료 행 정리는 별도 배치 없이 같은 키를 다시 쓸 때 여기서 치운다. */
    int deleteExpired(long userId, UUID idempotencyKey, Instant now);

    void insert(
            long userId,
            UUID idempotencyKey,
            String method,
            String path,
            String requestHash,
            int responseStatus,
            String responseBody,
            Instant now,
            Instant expiresAt);
}
