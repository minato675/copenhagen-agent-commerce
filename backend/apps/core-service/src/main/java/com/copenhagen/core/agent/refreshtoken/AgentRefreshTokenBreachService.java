package com.copenhagen.core.agent.refreshtoken;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 폐기된 Agent Refresh Token이 다시 제시되면 탈취로 보고 그 연동(connection)의 활성 토큰을 전부
 * 폐기한다. {@code auth.refreshtoken.RefreshTokenBreachService}와 같은 이유로 별도 트랜잭션에서
 * 즉시 커밋한다 — 호출자가 이 폐기 직후 401을 던지므로 같은 트랜잭션이면 롤백된다.
 *
 * <p>폐기 범위는 사용자 전체가 아니라 "그 connection"만이다 — 같은 사용자의 다른 Agent 연동까지
 * 끊을 이유가 없다.
 */
@Service
public class AgentRefreshTokenBreachService {

    private final AgentRefreshTokenRepository repository;
    private final Clock clock;

    public AgentRefreshTokenBreachService(AgentRefreshTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForConnection(long connectionId) {
        return repository.revokeAllActiveByConnectionId(connectionId, clock.instant());
    }
}
