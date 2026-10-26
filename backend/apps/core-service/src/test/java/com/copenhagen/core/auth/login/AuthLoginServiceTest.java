package com.copenhagen.core.auth.login;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.auth.jwt.CoreJwtIssuer;
import com.copenhagen.core.auth.jwt.IssuedAccessToken;
import com.copenhagen.core.auth.refreshtoken.IssuedRefreshToken;
import com.copenhagen.core.auth.refreshtoken.RefreshTokenService;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthLoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private LoginService loginService;
    private CoreJwtIssuer jwtIssuer;
    private RefreshTokenService refreshTokenService;
    private UserRepository userRepository;
    private AuthLoginService service;

    @BeforeEach
    void setUp() {
        loginService = mock(LoginService.class);
        jwtIssuer = mock(CoreJwtIssuer.class);
        refreshTokenService = mock(RefreshTokenService.class);
        userRepository = mock(UserRepository.class);
        service = new AuthLoginService(
                loginService, jwtIssuer, refreshTokenService, userRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("정상 로그인은 access/refresh/expiresIn을 모두 반환하고 last_login_at을 갱신한다")
    void login_success_returnsTokensAndUpdatesLastLoginAt() {
        LoginResult result = new LoginResult(UUID.randomUUID(), 42L, "user1234", "홍길동");
        when(loginService.login("user1234", "pw")).thenReturn(result);
        when(jwtIssuer.issue(result.userId())).thenReturn(new IssuedAccessToken("access-token", 1800L));
        when(refreshTokenService.issue(42L, "device-1", "iPhone 15"))
                .thenReturn(new IssuedRefreshToken("refresh-token", NOW.plusSeconds(1209600)));

        AuthLoginOutcome outcome = service.login("user1234", "pw", "device-1", "iPhone 15");

        assertEquals("access-token", outcome.accessToken());
        assertEquals("refresh-token", outcome.refreshToken());
        assertEquals(1800L, outcome.expiresInSeconds());
        assertEquals(result, outcome.loginResult());
        verify(userRepository).updateLastLoginAt(42L, NOW);
    }

    @Test
    @DisplayName("credential 검증이 실패하면 토큰 발급도 last_login_at 갱신도 일어나지 않는다")
    void login_credentialCheckFails_skipsTokenIssuanceAndLastLoginUpdate() {
        when(loginService.login("user1234", "wrong"))
                .thenThrow(new CoreException(CoreErrorCode.AUTH_INVALID_CREDENTIALS));

        assertThrows(CoreException.class, () -> service.login("user1234", "wrong", "device-1", "iPhone 15"));

        verifyNoInteractions(jwtIssuer, refreshTokenService, userRepository);
    }

    @Test
    @DisplayName("credential은 성공했지만 토큰 발급이 실패하면 last_login_at은 갱신되지 않는다 (INIT-02.3)")
    void login_tokenIssuanceFails_doesNotUpdateLastLoginAt() {
        LoginResult result = new LoginResult(UUID.randomUUID(), 42L, "user1234", "홍길동");
        when(loginService.login("user1234", "pw")).thenReturn(result);
        when(jwtIssuer.issue(any())).thenThrow(new IllegalStateException("signing failed"));

        assertThrows(IllegalStateException.class,
                () -> service.login("user1234", "pw", "device-1", "iPhone 15"));

        verify(userRepository, never()).updateLastLoginAt(eq(42L), any());
        verifyNoInteractions(refreshTokenService);
    }
}
