package com.copenhagen.core.auth.refreshtoken;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository {

    long insert(
            long userId,
            String tokenHash,
            String deviceId,
            String deviceName,
            Instant expiresAt,
            Instant now);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 회전·재사용 판정을 직렬화하기 위해 대상 행에 잠금을 건다. 호출자의 트랜잭션 안에서만 의미가 있다
     * (같은 토큰으로 동시에 들어온 갱신 요청이 둘 다 "활성"으로 읽는 것을 막는다).
     */
    Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash);

    /** 이미 폐기된 행은 건드리지 않는다(멱등). {@code last_used_at}도 함께 기록한다. */
    void revokeById(long id, Instant now);

    /** {@code revoked_at IS NULL}인 활성 토큰만 대상으로 한다. 해당하는 행이 없으면 아무 일도 하지 않는다. */
    void revokeActiveByUserIdAndDeviceId(long userId, String deviceId, Instant now);

    /** 탈취 의심 시 사용자의 활성 토큰을 기기 구분 없이 전부 폐기한다. 폐기된 행 수를 반환한다. */
    int revokeAllActiveByUserId(long userId, Instant now);
}
