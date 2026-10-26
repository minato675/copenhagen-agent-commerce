package com.copenhagen.core.auth.login;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.copenhagen.core.auth.jwt.CoreJwtIssuer;
import com.copenhagen.core.auth.jwt.IssuedAccessToken;
import com.copenhagen.core.auth.refreshtoken.IssuedRefreshToken;
import com.copenhagen.core.auth.refreshtoken.RefreshTokenService;
import com.copenhagen.core.auth.user.UserRepository;

/**
 * {@link LoginService}(credential 검증)에 토큰 발급과 {@code last_login_at} 갱신을 이어 붙인다. 토큰 발급이
 * 실패하면(서명 오류 등) 이 메서드가 예외를 전파하고 끝나며, {@code last_login_at} 갱신까지 도달하지 않는다 —
 * "최종 로그인 성공 기준"(비밀번호 일치 + 토큰 발급 성공)을 호출 순서만으로 충족한다.
 */
@Service
public class AuthLoginService {

    private final LoginService loginService;
    private final CoreJwtIssuer jwtIssuer;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final Clock clock;

    public AuthLoginService(
            LoginService loginService,
            CoreJwtIssuer jwtIssuer,
            RefreshTokenService refreshTokenService,
            UserRepository userRepository,
            Clock clock) {
        this.loginService = loginService;
        this.jwtIssuer = jwtIssuer;
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    public AuthLoginOutcome login(String loginId, String password, String deviceId, String deviceName) {
        LoginResult result = loginService.login(loginId, password);

        IssuedAccessToken accessToken = jwtIssuer.issue(result.userId());
        IssuedRefreshToken refreshToken =
                refreshTokenService.issue(result.userInternalId(), deviceId, deviceName);
        userRepository.updateLastLoginAt(result.userInternalId(), clock.instant());

        return new AuthLoginOutcome(
                accessToken.token(), refreshToken.plainToken(), accessToken.expiresInSeconds(), result);
    }
}
