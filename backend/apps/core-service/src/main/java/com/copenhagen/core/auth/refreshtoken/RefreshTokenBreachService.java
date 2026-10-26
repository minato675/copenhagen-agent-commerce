package com.copenhagen.core.auth.refreshtoken;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 폐기된 Refresh Token이 다시 제시되면 탈취로 보고 해당 사용자의 활성 토큰을 전부 폐기한다.
 *
 * <p>호출자는 이 폐기 직후 {@code 401}을 던지는데, 같은 트랜잭션에서 폐기하면 예외와 함께 롤백되어 탈취 대응이
 * 사라진다. 그래서 {@link Propagation#REQUIRES_NEW}로 분리해 즉시 커밋한다 — 실패 기록을 분리한
 * {@code LoginFailureService}(구현순서 2)와 같은 이유·같은 패턴이다.
 */
@Service
public class RefreshTokenBreachService {

    private final RefreshTokenRepository repository;
    private final Clock clock;

    public RefreshTokenBreachService(RefreshTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(long userId) {
        return repository.revokeAllActiveByUserId(userId, clock.instant());
    }
}
