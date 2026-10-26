package com.copenhagen.core.agent.refreshtoken;

import java.time.Instant;
import java.util.Optional;

public interface AgentRefreshTokenRepository {

    long insert(long connectionId, String tokenHash, String scopesJson, Instant expiresAt, Instant now);

    Optional<AgentRefreshToken> findByTokenHash(String tokenHash);

    /** 회전·재사용 판정을 직렬화하기 위해 대상 행에 잠금을 건다({@code auth.refreshtoken}과 동일 이유). */
    Optional<AgentRefreshToken> findByTokenHashForUpdate(String tokenHash);

    void revokeById(long id, Instant now);

    /** 연동 해제 시 그 connection의 활성 토큰을 전부 폐기한다. 폐기된 행 수를 반환한다. */
    int revokeAllActiveByConnectionId(long connectionId, Instant now);
}
