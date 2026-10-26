package com.copenhagen.core.auth.login;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginFailureServiceTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final long ACCOUNT_ID = 1L;

    private FakeLocalAccountRepository repository;
    private MutableClock clock;
    private LoginFailureService service;

    @BeforeEach
    void setUp() {
        repository = new FakeLocalAccountRepository();
        clock = new MutableClock(BASE_INSTANT);
        service = new LoginFailureService(repository, clock);

        repository.seed(ACCOUNT_ID, 0, null, null);
    }

    @Test
    @DisplayName("1~4번째 실패는 잠그지 않고 횟수만 증가시킨다")
    void record_belowThreshold_incrementsWithoutLocking() {
        for (int i = 1; i <= 4; i++) {
            LoginFailureService.FailureResult result = service.record(ACCOUNT_ID);
            assertEquals(i, result.failedLoginCount());
            assertFalse(result.locked());
        }
    }

    @Test
    @DisplayName("5번째 실패는 잠그고 15분 뒤 locked_until을 반환한다")
    void record_fifthFailure_locksForFifteenMinutes() {
        for (int i = 0; i < 4; i++) {
            service.record(ACCOUNT_ID);
        }

        LoginFailureService.FailureResult result = service.record(ACCOUNT_ID);

        assertEquals(5, result.failedLoginCount());
        assertTrue(result.locked());
        assertEquals(BASE_INSTANT.plus(Duration.ofMinutes(15)), result.lockedUntil());
    }

    @Test
    @DisplayName("만료된 잠금 상태에서 다시 실패하면 1번째 실패로 취급한다")
    void record_afterLockExpired_treatsAsNewCycle() {
        for (int i = 0; i < 5; i++) {
            service.record(ACCOUNT_ID);
        }
        clock.advance(Duration.ofMinutes(15));

        LoginFailureService.FailureResult result = service.record(ACCOUNT_ID);

        assertEquals(1, result.failedLoginCount());
        assertFalse(result.locked());
        assertNull(repository.byId.get(ACCOUNT_ID).lockedUntil());
    }

    private static class FakeLocalAccountRepository implements LocalAccountRepository {

        private final Map<Long, LocalAccount> byId = new HashMap<>();

        void seed(long id, int failedLoginCount, Instant lastFailedLoginAt, Instant lockedUntil) {
            byId.put(id, new LocalAccount(id, 1L, "user1234", "hash", failedLoginCount,
                    lastFailedLoginAt, lockedUntil, BASE_INSTANT, BASE_INSTANT));
        }

        @Override
        public boolean existsByLoginId(String loginId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LocalAccount> findByLoginId(String loginId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LocalAccount> findByIdForUpdate(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<LocalAccount> findByUserId(long userId) {
            throw new UnsupportedOperationException();
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
