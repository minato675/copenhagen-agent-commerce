package com.copenhagen.core.auth.localaccount;

import java.time.Instant;
import java.util.Optional;

public interface LocalAccountRepository {

    boolean existsByLoginId(String loginId);

    Optional<LocalAccount> findByLoginId(String loginId);

    Optional<LocalAccount> findByUserId(long userId);

    /**
     * 실패 카운트·잠금 갱신 시 동시 실패 요청 간 경쟁을 막기 위해 사용한다. 로그인 주 흐름(비밀번호 검증)에서는
     * 사용하지 않는다 — 실패 기록은 별도 트랜잭션(REQUIRES_NEW)에서 수행되므로, 주 흐름이 같은 행을 먼저 잠그면
     * 자기 자신과의 교착 상태가 된다.
     */
    Optional<LocalAccount> findByIdForUpdate(long id);

    long insert(long userId, String loginId, String passwordHash, Instant now);

    void recordLoginFailure(
            long id, int failedLoginCount, Instant lastFailedLoginAt, Instant lockedUntil, Instant now);

    /**
     * {@code failed_login_count}·{@code locked_until}만 초기화한다. {@code last_failed_login_at}은 감사 기록으로
     * 유지한다(INIT-05.2).
     */
    void resetLoginFailure(long id, Instant now);
}
