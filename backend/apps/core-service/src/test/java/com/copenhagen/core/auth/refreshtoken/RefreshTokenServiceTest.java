package com.copenhagen.core.auth.refreshtoken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RefreshTokenServiceTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER_ID = 1L;
    private static final String DEVICE_ID = "device-1";
    private static final String DEVICE_NAME = "iPhone 15";

    private FakeRefreshTokenRepository repository;
    private RefreshTokenBreachService breachService;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRefreshTokenRepository();
        breachService = mock(RefreshTokenBreachService.class);
        service = new RefreshTokenService(
                repository, breachService, Clock.fixed(BASE_INSTANT, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("신규 발급 시 평문 토큰과 만료 시각(14일 뒤)을 반환하고, 저장값은 해시다")
    void issue_newToken_returnsPlainTokenAndPersistsHashOnly() {
        IssuedRefreshToken issued = service.issue(USER_ID, DEVICE_ID, DEVICE_NAME);

        assertNotNull(issued.plainToken());
        assertEquals(BASE_INSTANT.plus(Duration.ofDays(14)), issued.expiresAt());

        RefreshToken saved = repository.rows.get(0);
        assertEquals(sha256Hex(issued.plainToken()), saved.tokenHash());
        assertNotEquals(issued.plainToken(), saved.tokenHash());
    }

    @Test
    @DisplayName("같은 (user, device)로 재로그인하면 이전 활성 토큰을 폐기하고 새 행을 추가한다")
    void issue_sameUserAndDevice_revokesPreviousActiveToken() {
        service.issue(USER_ID, DEVICE_ID, DEVICE_NAME);
        service.issue(USER_ID, DEVICE_ID, DEVICE_NAME);

        assertEquals(2, repository.rows.size());
        assertTrue(repository.revokedCalls.contains(USER_ID + ":" + DEVICE_ID));
    }

    @Test
    @DisplayName("서로 다른 device는 독립적인 세션을 가진다(폐기하지 않음)")
    void issue_differentDevice_doesNotRevokeOtherDeviceToken() {
        service.issue(USER_ID, DEVICE_ID, DEVICE_NAME);
        service.issue(USER_ID, "device-2", "iPad");

        assertEquals(2, repository.rows.size());
    }

    @Test
    @DisplayName("활성 토큰을 검증하면 해당 행을 그대로 반환한다")
    void verifyForRotation_activeToken_returnsRow() {
        String plainToken = "plain-token";
        RefreshToken seeded = seedActive(plainToken);

        RefreshToken verified = service.verifyForRotation(plainToken);

        assertEquals(seeded.id(), verified.id());
        verify(breachService, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("존재하지 않는 토큰이면 AUTH_REFRESH_TOKEN_INVALID로 거부한다")
    void verifyForRotation_unknownToken_throwsInvalid() {
        CoreException exception = assertThrows(
                CoreException.class, () -> service.verifyForRotation("unknown-token"));

        assertEquals(CoreErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.errorCode());
        verify(breachService, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("만료된 토큰은 탈취가 아니므로 전체 폐기 없이 AUTH_REFRESH_TOKEN_INVALID로만 거부한다")
    void verifyForRotation_expiredToken_throwsInvalidWithoutBreachHandling() {
        String plainToken = "expired-token";
        repository.seed(
                USER_ID, sha256Hex(plainToken), DEVICE_ID, DEVICE_NAME,
                BASE_INSTANT.minus(Duration.ofDays(20)), BASE_INSTANT.minus(Duration.ofDays(6)), null);

        CoreException exception = assertThrows(
                CoreException.class, () -> service.verifyForRotation(plainToken));

        assertEquals(CoreErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.errorCode());
        verify(breachService, never()).revokeAllForUser(anyLong());
    }

    @Test
    @DisplayName("이미 폐기된 토큰을 다시 쓰면 사용자 전체 토큰을 폐기하고 AUTH_REFRESH_TOKEN_REUSED로 거부한다")
    void verifyForRotation_revokedToken_revokesAllAndThrowsReused() {
        String plainToken = "revoked-token";
        repository.seed(
                USER_ID, sha256Hex(plainToken), DEVICE_ID, DEVICE_NAME,
                BASE_INSTANT, BASE_INSTANT.plus(Duration.ofDays(14)), BASE_INSTANT);

        CoreException exception = assertThrows(
                CoreException.class, () -> service.verifyForRotation(plainToken));

        assertEquals(CoreErrorCode.AUTH_REFRESH_TOKEN_REUSED, exception.errorCode());
        verify(breachService).revokeAllForUser(USER_ID);
    }

    @Test
    @DisplayName("회전하면 기존 행을 폐기하고 device 정보를 승계한 새 토큰을 발급한다")
    void rotate_activeToken_revokesCurrentAndIssuesNewTokenForSameDevice() {
        String plainToken = "plain-token";
        RefreshToken current = seedActive(plainToken);

        IssuedRefreshToken rotated = service.rotate(current);

        RefreshToken revoked = repository.findById(current.id()).orElseThrow();
        assertEquals(BASE_INSTANT, revoked.revokedAt());
        assertEquals(BASE_INSTANT, revoked.lastUsedAt());

        RefreshToken issued = repository.rows.get(repository.rows.size() - 1);
        assertEquals(sha256Hex(rotated.plainToken()), issued.tokenHash());
        assertNotEquals(plainToken, rotated.plainToken());
        assertEquals(DEVICE_ID, issued.deviceId());
        assertEquals(DEVICE_NAME, issued.deviceName());
        assertNull(issued.revokedAt());
    }

    @Test
    @DisplayName("회전으로 발급된 토큰의 만료는 회전 시점 기준 14일이다")
    void rotate_activeToken_setsFourteenDayExpiry() {
        IssuedRefreshToken rotated = service.rotate(seedActive("plain-token"));

        assertEquals(BASE_INSTANT.plus(Duration.ofDays(14)), rotated.expiresAt());
    }

    @Test
    @DisplayName("로그아웃은 해당 토큰만 폐기한다")
    void revoke_activeToken_marksRevoked() {
        String plainToken = "plain-token";
        RefreshToken seeded = seedActive(plainToken);

        service.revoke(plainToken);

        assertEquals(BASE_INSTANT, repository.findById(seeded.id()).orElseThrow().revokedAt());
    }

    @Test
    @DisplayName("존재하지 않는 토큰으로 로그아웃해도 예외 없이 아무 일도 하지 않는다")
    void revoke_unknownToken_doesNothing() {
        service.revoke("unknown-token");

        assertTrue(repository.rows.isEmpty());
    }

    @Test
    @DisplayName("이미 폐기된 토큰으로 로그아웃해도 예외 없이 폐기 시각을 덮어쓰지 않는다")
    void revoke_alreadyRevokedToken_keepsOriginalRevokedAt() {
        String plainToken = "revoked-token";
        Instant revokedAt = BASE_INSTANT.minus(Duration.ofHours(1));
        RefreshToken seeded = repository.seed(
                USER_ID, sha256Hex(plainToken), DEVICE_ID, DEVICE_NAME,
                BASE_INSTANT.minus(Duration.ofDays(1)), BASE_INSTANT.plus(Duration.ofDays(13)), revokedAt);

        service.revoke(plainToken);

        assertEquals(revokedAt, repository.findById(seeded.id()).orElseThrow().revokedAt());
    }

    private RefreshToken seedActive(String plainToken) {
        return repository.seed(
                USER_ID, sha256Hex(plainToken), DEVICE_ID, DEVICE_NAME,
                BASE_INSTANT, BASE_INSTANT.plus(Duration.ofDays(14)), null);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
