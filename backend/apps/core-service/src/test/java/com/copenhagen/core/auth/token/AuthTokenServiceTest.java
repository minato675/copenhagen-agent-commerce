package com.copenhagen.core.auth.token;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.auth.jwt.CoreJwtIssuer;
import com.copenhagen.core.auth.jwt.IssuedAccessToken;
import com.copenhagen.core.auth.refreshtoken.IssuedRefreshToken;
import com.copenhagen.core.auth.refreshtoken.RefreshToken;
import com.copenhagen.core.auth.refreshtoken.RefreshTokenService;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER_ID = 42L;
    private static final String PLAIN_TOKEN = "plain-refresh-token";

    private RefreshTokenService refreshTokenService;
    private UserRepository userRepository;
    private CoreJwtIssuer jwtIssuer;
    private AuthTokenService service;

    @BeforeEach
    void setUp() {
        refreshTokenService = mock(RefreshTokenService.class);
        userRepository = mock(UserRepository.class);
        jwtIssuer = mock(CoreJwtIssuer.class);
        service = new AuthTokenService(refreshTokenService, userRepository, jwtIssuer);
    }

    @Test
    @DisplayName("정상 갱신은 새 access/refresh와 expiresIn을 반환한다")
    void refresh_activeUser_returnsRotatedTokens() {
        RefreshToken current = activeToken();
        User user = user(UserStatus.ACTIVE);
        when(refreshTokenService.verifyForRotation(PLAIN_TOKEN)).thenReturn(current);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenService.rotate(current))
                .thenReturn(new IssuedRefreshToken("new-refresh-token", NOW.plus(Duration.ofDays(14))));
        when(jwtIssuer.issue(user.publicId())).thenReturn(new IssuedAccessToken("new-access-token", 1800L));

        TokenRefreshOutcome outcome = service.refresh(PLAIN_TOKEN);

        assertEquals("new-access-token", outcome.accessToken());
        assertEquals("new-refresh-token", outcome.refreshToken());
        assertEquals(1800L, outcome.expiresInSeconds());
    }

    @Test
    @DisplayName("정지된 계정은 403 AUTH_ACCOUNT_UNAVAILABLE이며 토큰을 회전하지 않는다")
    void refresh_suspendedUser_throwsAccountUnavailableWithoutRotation() {
        assertRejectsInactiveStatus(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("탈퇴한 계정도 403 AUTH_ACCOUNT_UNAVAILABLE이며 토큰을 회전하지 않는다")
    void refresh_withdrawnUser_throwsAccountUnavailableWithoutRotation() {
        assertRejectsInactiveStatus(UserStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("토큰은 유효하지만 사용자 행이 없으면 AUTH_REFRESH_TOKEN_INVALID로 거부한다")
    void refresh_missingUser_throwsInvalid() {
        when(refreshTokenService.verifyForRotation(PLAIN_TOKEN)).thenReturn(activeToken());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        CoreException exception = assertThrows(CoreException.class, () -> service.refresh(PLAIN_TOKEN));

        assertEquals(CoreErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.errorCode());
        verify(refreshTokenService, never()).rotate(any());
    }

    @Test
    @DisplayName("로그아웃은 해당 Refresh Token 폐기만 위임한다")
    void logout_delegatesRevoke() {
        service.logout(PLAIN_TOKEN);

        verify(refreshTokenService).revoke(PLAIN_TOKEN);
        verifyNoInteractions(jwtIssuer);
    }

    private void assertRejectsInactiveStatus(UserStatus status) {
        when(refreshTokenService.verifyForRotation(PLAIN_TOKEN)).thenReturn(activeToken());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(status)));

        CoreException exception = assertThrows(CoreException.class, () -> service.refresh(PLAIN_TOKEN));

        assertEquals(CoreErrorCode.AUTH_ACCOUNT_UNAVAILABLE, exception.errorCode());
        verify(refreshTokenService, never()).rotate(any());
        verifyNoInteractions(jwtIssuer);
    }

    private static RefreshToken activeToken() {
        return new RefreshToken(
                7L, USER_ID, "token-hash", "device-1", "iPhone 15",
                NOW, NOW.plus(Duration.ofDays(14)), null, null, NOW);
    }

    private static User user(UserStatus status) {
        return new User(
                USER_ID, UUID.randomUUID(), "홍길동", status,
                EncryptedPii.of(new byte[] {1}), "email-hash", true,
                null, null, null, null, null, NOW, NOW);
    }
}
