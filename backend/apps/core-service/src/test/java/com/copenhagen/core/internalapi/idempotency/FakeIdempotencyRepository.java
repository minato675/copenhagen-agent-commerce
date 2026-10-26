package com.copenhagen.core.internalapi.idempotency;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeIdempotencyRepository implements IdempotencyRepository {

    final List<Row> rows = new ArrayList<>();

    @Override
    public Optional<IdempotencyRecord> find(long userId, UUID idempotencyKey, Instant now) {
        return rows.stream()
                .filter(row -> row.userId == userId && row.key.equals(idempotencyKey)
                        && row.expiresAt.isAfter(now))
                .findFirst()
                .map(row -> new IdempotencyRecord(row.requestHash, row.responseStatus, row.responseBody));
    }

    @Override
    public int deleteExpired(long userId, UUID idempotencyKey, Instant now) {
        return rows.removeIf(row -> row.userId == userId && row.key.equals(idempotencyKey)
                && !row.expiresAt.isAfter(now)) ? 1 : 0;
    }

    @Override
    public void insert(
            long userId,
            UUID idempotencyKey,
            String method,
            String path,
            String requestHash,
            int responseStatus,
            String responseBody,
            Instant now,
            Instant expiresAt) {
        rows.add(new Row(userId, idempotencyKey, requestHash, responseStatus, responseBody, expiresAt));
    }

    private record Row(
            long userId,
            UUID key,
            String requestHash,
            int responseStatus,
            String responseBody,
            Instant expiresAt) {
    }
}
