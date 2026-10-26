package com.copenhagen.core.auth.login;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.copenhagen.core.auth.AuthConfiguration;
import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginServiceTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String RAW_PASSWORD = "Password1!";

    private FakeUserRepository userRepository;
    private FakeLocalAccountRepository localAccountRepository;
    private PasswordEncoder passwordEncoder;
    private MutableClock clock;
    private LoginService service;

    private long userId;
    private long localAccountId;

    @BeforeEach
    void setUp() {
        userRepository = new FakeUserRepository();
        localAccountRepository = new FakeLocalAccountRepository();
        passwordEncoder = new BCryptPasswordEncoder(AuthConfiguration.BCRYPT_STRENGTH);
        clock = new MutableClock(BASE_INSTANT);
        service = new LoginService(
                localAccountRepository,
                userRepository,
                passwordEncoder,
                new LoginFailureService(localAccountRepository, clock),
                clock);

        userId = userRepository.seed(UserStatus.ACTIVE);
        localAccountId = localAccountRepository.seed(userId, "user1234", passwordEncoder.encode(RAW_PASSWORD));
    }

    @Test
    @DisplayName("정상 credential이면 로그인에 성공하고 결과를 반환한다")
    void login_validCredentials_succeeds() {
        LoginResult result = service.login("user1234", RAW_PASSWORD);

        assertEquals("user1234", result.loginId());
        assertEquals(userId, result.userInternalId());
    }

    @Test
    @DisplayName("존재하지 않는 loginId는 401을 던지고 실패 횟수를 기록하지 않는다")
    void login_unknownLoginId_returnsInvalidCredentialsWithoutRecordingFailure() {
        CoreException exception = assertThrows(CoreException.class,
                () -> service.login("no-such-user", RAW_PASSWORD));

        assertEquals(CoreErrorCode.AUTH_INVALID_CREDENTIALS, exception.errorCode());
    }

    @Test
    @DisplayName("틀린 비밀번호는 401을 던지고 실패 횟수를 1 증가시킨다")
    void login_wrongPassword_recordsFailure() {
        CoreException exception = assertThrows(CoreException.class,
                () -> service.login("user1234", "WrongPassword1!"));

        assertEquals(CoreErrorCode.AUTH_INVALID_CREDENTIALS, exception.errorCode());
        assertEquals(1, localAccountRepository.byId.get(localAccountId).failedLoginCount());
    }

    @Test
    @DisplayName("5번째 실패에서 423으로 잠기고 locked_until이 15분 뒤로 설정된다")
    void login_fifthFailure_locksAccount() {
        for (int i = 0; i < 4; i++) {
            assertThrows(CoreException.class, () -> service.login("user1234", "WrongPassword1!"));
        }

        CoreException exception = assertThrows(CoreException.class,
                () -> service.login("user1234", "WrongPassword1!"));

        assertEquals(CoreErrorCode.AUTH_INVALID_CREDENTIALS, exception.errorCode());
        LocalAccount account = localAccountRepository.byId.get(localAccountId);
        assertEquals(5, account.failedLoginCount());
        assertEquals(BASE_INSTANT.plus(Duration.ofMinutes(15)), account.lockedUntil());
    }

    @Test
    @DisplayName("잠금 중에는 올바른 비밀번호를 입력해도 423으로 즉시 거부한다")
    void login_whileLocked_rejectsEvenWithCorrectPassword() {
        lockAccount();

        CoreException exception = assertThrows(CoreException.class,
                () -> service.login("user1234", RAW_PASSWORD));

        assertEquals(CoreErrorCode.AUTH_LOGIN_LOCKED, exception.errorCode());
        assertEquals(Duration.ofMinutes(15).toSeconds(), exception.retryAfterSeconds());
    }

    @Test
    @DisplayName("잠금 만료 후에는 올바른 비밀번호로 로그인에 성공하고 실패 횟수가 초기화된다")
    void login_afterLockExpires_succeedsAndResetsFailureCount() {
        lockAccount();
        clock.advance(Duration.ofMinutes(15));

        LoginResult result = service.login("user1234", RAW_PASSWORD);

        assertEquals("user1234", result.loginId());
        LocalAccount account = localAccountRepository.byId.get(localAccountId);
        assertEquals(0, account.failedLoginCount());
        assertNull(account.lockedUntil());
    }

    @Test
    @DisplayName("잠금 만료 후 다시 실패하면 새 주기의 1번째 실패로 기록된다(즉시 재잠금하지 않음)")
    void login_afterLockExpires_wrongPasswordStartsNewFailureCycle() {
        lockAccount();
        clock.advance(Duration.ofMinutes(15));

        assertThrows(CoreException.class, () -> service.login("user1234", "WrongPassword1!"));

        LocalAccount account = localAccountRepository.byId.get(localAccountId);
        assertEquals(1, account.failedLoginCount());
        assertNull(account.lockedUntil());
    }

    @Test
    @DisplayName("성공한 로그인은 실패 횟수·잠금을 초기화한다")
    void login_success_resetsFailureCount() {
        assertThrows(CoreException.class, () -> service.login("user1234", "WrongPassword1!"));
        assertThrows(CoreException.class, () -> service.login("user1234", "WrongPassword1!"));

        service.login("user1234", RAW_PASSWORD);

        LocalAccount account = localAccountRepository.byId.get(localAccountId);
        assertEquals(0, account.failedLoginCount());
        assertNull(account.lockedUntil());
    }

    @Test
    @DisplayName("SUSPENDED 계정은 비밀번호가 맞아도 403으로 거부하고 실패 횟수를 기록하지 않는다")
    void login_suspendedAccount_rejectsRegardlessOfPassword() {
        userRepository.setStatus(userId, UserStatus.SUSPENDED);

        CoreException exception = assertThrows(CoreException.class,
                () -> service.login("user1234", RAW_PASSWORD));

        assertEquals(CoreErrorCode.AUTH_ACCOUNT_UNAVAILABLE, exception.errorCode());
        assertEquals(0, localAccountRepository.byId.get(localAccountId).failedLoginCount());
    }

    @Test
    @DisplayName("WITHDRAWN 계정은 SUSPENDED와 동일하게 403으로 거부한다")
    void login_withdrawnAccount_rejectsWithSameError() {
        userRepository.setStatus(userId, UserStatus.WITHDRAWN);

        CoreException exception = assertThrows(CoreException.class,
                () -> service.login("user1234", RAW_PASSWORD));

        assertEquals(CoreErrorCode.AUTH_ACCOUNT_UNAVAILABLE, exception.errorCode());
    }

    private void lockAccount() {
        for (int i = 0; i < 5; i++) {
            assertThrows(CoreException.class, () -> service.login("user1234", "WrongPassword1!"));
        }
    }

    // ===== 테스트 대역 =====

    private static class FakeUserRepository implements UserRepository {

        private final Map<Long, User> byId = new HashMap<>();
        private long nextId = 1;

        long seed(UserStatus status) {
            long id = nextId++;
            byId.put(id, new User(id, UUID.randomUUID(), "홍길동", status,
                    null, null, false, null, null, null, null, null, BASE_INSTANT, BASE_INSTANT));
            return id;
        }

        void setStatus(long id, UserStatus status) {
            User existing = byId.get(id);
            byId.put(id, new User(existing.id(), existing.publicId(), existing.name(), status,
                    existing.emailEncrypted(), existing.emailHash(), existing.emailVerified(),
                    existing.phoneEncrypted(), existing.phoneHash(), existing.deletedAt(),
                    existing.pwChangedAt(), existing.lastLoginAt(), existing.createdAt(), existing.updatedAt()));
        }

        @Override
        public long insert(UUID publicId, String name, UserStatus status,
                EncryptedPii emailEncrypted, String emailHash, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsByEmailHash(String emailHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<User> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public void updateLastLoginAt(long id, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<User> findByPublicId(UUID publicId) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeLocalAccountRepository implements LocalAccountRepository {

        private final Map<Long, LocalAccount> byId = new HashMap<>();
        private final Map<String, Long> idByLoginId = new HashMap<>();
        private long nextId = 1;

        long seed(long userId, String loginId, String passwordHash) {
            long id = nextId++;
            idByLoginId.put(loginId, id);
            byId.put(id, new LocalAccount(id, userId, loginId, passwordHash, 0, null, null,
                    BASE_INSTANT, BASE_INSTANT));
            return id;
        }

        @Override
        public boolean existsByLoginId(String loginId) {
            return idByLoginId.containsKey(loginId);
        }

        @Override
        public Optional<LocalAccount> findByLoginId(String loginId) {
            Long id = idByLoginId.get(loginId);
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<LocalAccount> findByIdForUpdate(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<LocalAccount> findByUserId(long userId) {
            return byId.values().stream().filter(account -> account.userId() == userId).findFirst();
        }

        @Override
        public long insert(long userId, String loginId, String passwordHash, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordLoginFailure(
                long id, int failedLoginCount, Instant lastFailedLoginAt, Instant lockedUntil, Instant now) {
            LocalAccount existing = byId.get(id);
            byId.put(id, new LocalAccount(existing.id(), existing.userId(), existing.loginId(),
                    existing.passwordHash(), failedLoginCount, lastFailedLoginAt, lockedUntil,
                    existing.createdAt(), now));
        }

        @Override
        public void resetLoginFailure(long id, Instant now) {
            LocalAccount existing = byId.get(id);
            byId.put(id, new LocalAccount(existing.id(), existing.userId(), existing.loginId(),
                    existing.passwordHash(), 0, existing.lastFailedLoginAt(), null,
                    existing.createdAt(), now));
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
