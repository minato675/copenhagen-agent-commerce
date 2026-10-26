package com.copenhagen.core.auth.refreshtoken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 단위 테스트용 인메모리 저장소. 행 잠금은 의미가 없으므로 {@code findByTokenHashForUpdate}는 일반 조회와 같다. */
class FakeRefreshTokenRepository implements RefreshTokenRepository {

    final List<RefreshToken> rows = new ArrayList<>();
    final List<String> revokedCalls = new ArrayList<>();
    private long nextId = 1;

    @Override
    public long insert(
            long userId, String tokenHash, String deviceId, String deviceName, Instant expiresAt, Instant now) {
        long id = nextId++;
        rows.add(new RefreshToken(id, userId, tokenHash, deviceId, deviceName, now, expiresAt, null, null, now));
        return id;
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return rows.stream().filter(row -> row.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash) {
        return findByTokenHash(tokenHash);
    }

    @Override
    public void revokeById(long id, Instant now) {
        replace(row -> row.id() == id && row.revokedAt() == null, now, now);
    }

    @Override
    public void revokeActiveByUserIdAndDeviceId(long userId, String deviceId, Instant now) {
        revokedCalls.add(userId + ":" + deviceId);
        replace(
                row -> row.userId() == userId && row.deviceId().equals(deviceId) && row.revokedAt() == null,
                now,
                null);
    }

    @Override
    public int revokeAllActiveByUserId(long userId, Instant now) {
        return replace(row -> row.userId() == userId && row.revokedAt() == null, now, null);
    }

    /** 조건에 맞는 행을 폐기 상태로 바꾼다. {@code lastUsedAt}이 {@code null}이면 기존 값을 유지한다. */
    private int replace(java.util.function.Predicate<RefreshToken> match, Instant revokedAt, Instant lastUsedAt) {
        int affected = 0;
        for (int i = 0; i < rows.size(); i++) {
            RefreshToken row = rows.get(i);
            if (!match.test(row)) {
                continue;
            }
            rows.set(i, new RefreshToken(
                    row.id(), row.userId(), row.tokenHash(), row.deviceId(), row.deviceName(),
                    row.issuedAt(), row.expiresAt(), revokedAt,
                    lastUsedAt == null ? row.lastUsedAt() : lastUsedAt, row.createdAt()));
            affected++;
        }
        return affected;
    }

    /** 테스트가 미리 심어 두는 행. 만료·폐기 상태를 직접 지정한다. */
    RefreshToken seed(
            long userId, String tokenHash, String deviceId, String deviceName,
            Instant issuedAt, Instant expiresAt, Instant revokedAt) {
        long id = nextId++;
        RefreshToken row = new RefreshToken(
                id, userId, tokenHash, deviceId, deviceName, issuedAt, expiresAt, revokedAt, null, issuedAt);
        rows.add(row);
        return row;
    }

    Optional<RefreshToken> findById(long id) {
        return rows.stream().filter(row -> row.id() == id).findFirst();
    }
}
