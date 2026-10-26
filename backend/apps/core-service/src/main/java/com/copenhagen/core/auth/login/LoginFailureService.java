package com.copenhagen.core.auth.login;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * 로그인 실패 기록은 자체 트랜잭션(REQUIRES_NEW)으로 커밋한다. {@link LoginService}가 실패를 이유로 예외를
 * 던져 바깥 트랜잭션이 롤백되더라도, 이미 기록한 실패 횟수·잠금은 되돌리지 않아야 하기 때문이다.
 */
@Service
public class LoginFailureService {

    static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(15);

    private final LocalAccountRepository localAccountRepository;
    private final Clock clock;

    public LoginFailureService(LocalAccountRepository localAccountRepository, Clock clock) {
        this.localAccountRepository = localAccountRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureResult record(long localAccountId) {
        LocalAccount account = localAccountRepository.findByIdForUpdate(localAccountId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.INTERNAL_ERROR));
        Instant now = clock.instant();

        // 이전 잠금이 이미 만료됐다면 새 주기로 취급한다(INIT-05.2: 잠금 만료 후 다음 요청에서 실패 횟수 초기화).
        boolean previousLockExpired = account.lockedUntil() != null && !now.isBefore(account.lockedUntil());
        int baseFailedLoginCount = previousLockExpired ? 0 : account.failedLoginCount();

        int failedLoginCount = baseFailedLoginCount + 1;
        Instant lockedUntil = failedLoginCount >= MAX_FAILED_LOGIN_ATTEMPTS ? now.plus(LOGIN_LOCK_DURATION) : null;
        localAccountRepository.recordLoginFailure(account.id(), failedLoginCount, now, lockedUntil, now);

        return new FailureResult(failedLoginCount, lockedUntil);
    }

    public record FailureResult(int failedLoginCount, Instant lockedUntil) {

        public boolean locked() {
            return lockedUntil != null;
        }
    }
}
