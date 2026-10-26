package com.copenhagen.core.auth.login;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * credential 검증까지만 담당한다. 토큰 발급과 {@code users.last_login_at} 갱신은 구현순서 5에서 이 메서드
 * 호출 뒤에 이어 붙인다(INIT-02.3의 "최종 성공"은 토큰 발급까지 포함하므로 이 서비스의 성공 반환만으로는
 * 아직 로그인이 최종 완료된 것이 아니다).
 */
@Service
public class LoginService {

    private final LocalAccountRepository localAccountRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginFailureService loginFailureService;
    private final Clock clock;

    public LoginService(
            LocalAccountRepository localAccountRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            LoginFailureService loginFailureService,
            Clock clock) {
        this.localAccountRepository = localAccountRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginFailureService = loginFailureService;
        this.clock = clock;
    }

    @Transactional
    public LoginResult login(String loginId, String password) {
        LocalAccount account = localAccountRepository.findByLoginId(loginId)
                .orElseThrow(LoginService::invalidCredentials);
        User user = userRepository.findById(account.userId())
                .orElseThrow(LoginService::invalidCredentials);

        ensureActive(user);
        ensureNotLocked(account);

        if (!passwordEncoder.matches(password, account.passwordHash())) {
            loginFailureService.record(account.id());
            throw invalidCredentials();
        }

        localAccountRepository.resetLoginFailure(account.id(), clock.instant());
        return new LoginResult(user.publicId(), user.id(), account.loginId(), user.name());
    }

    private void ensureActive(User user) {
        if (user.status() != UserStatus.ACTIVE) {
            throw new CoreException(CoreErrorCode.AUTH_ACCOUNT_UNAVAILABLE);
        }
    }

    /** 잠금이 이미 만료됐다면 여기서는 그대로 통과시킨다 — 초기화는 {@link LoginFailureService}가 다음 실패 기록 시점에 처리한다. */
    private void ensureNotLocked(LocalAccount account) {
        Instant lockedUntil = account.lockedUntil();
        Instant now = clock.instant();
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            long retryAfterSeconds = Duration.between(now, lockedUntil).toSeconds();
            throw new CoreException(
                    CoreErrorCode.AUTH_LOGIN_LOCKED,
                    CoreErrorCode.AUTH_LOGIN_LOCKED.defaultMessage(),
                    null,
                    "TRY_AGAIN_AFTER_" + lockedUntil,
                    retryAfterSeconds,
                    null);
        }
    }

    private static CoreException invalidCredentials() {
        return new CoreException(CoreErrorCode.AUTH_INVALID_CREDENTIALS);
    }
}
