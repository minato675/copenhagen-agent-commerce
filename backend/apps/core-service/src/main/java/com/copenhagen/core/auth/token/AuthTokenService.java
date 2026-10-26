package com.copenhagen.core.auth.token;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.auth.jwt.CoreJwtIssuer;
import com.copenhagen.core.auth.jwt.IssuedAccessToken;
import com.copenhagen.core.auth.refreshtoken.IssuedRefreshToken;
import com.copenhagen.core.auth.refreshtoken.RefreshToken;
import com.copenhagen.core.auth.refreshtoken.RefreshTokenService;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * {@code AuthLoginService}가 {@code LoginService}를 감싸는 것과 같은 오케스트레이션 계층이다. Refresh Token
 * 검증·회전({@link RefreshTokenService})에 계정 상태 검사와 Access Token 발급을 이어 붙인다.
 */
@Service
public class AuthTokenService {

    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final CoreJwtIssuer jwtIssuer;

    public AuthTokenService(
            RefreshTokenService refreshTokenService,
            UserRepository userRepository,
            CoreJwtIssuer jwtIssuer) {
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
    }

    /**
     * 계정 상태 검사를 회전보다 먼저 둔다 — 이용할 수 없는 계정의 Refresh Token을 굳이 폐기하지 않는다.
     * Access Token 발급은 회전 뒤에 두어, 서명이 실패하면 회전까지 함께 롤백되게 한다(새 토큰만 저장되고
     * 응답은 실패하는 상태를 만들지 않는다).
     */
    @Transactional
    public TokenRefreshOutcome refresh(String plainRefreshToken) {
        RefreshToken current = refreshTokenService.verifyForRotation(plainRefreshToken);

        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_REFRESH_TOKEN_INVALID));
        if (user.status() != UserStatus.ACTIVE) {
            throw new CoreException(CoreErrorCode.AUTH_ACCOUNT_UNAVAILABLE);
        }

        IssuedRefreshToken rotated = refreshTokenService.rotate(current);
        IssuedAccessToken accessToken = jwtIssuer.issue(user.publicId());

        return new TokenRefreshOutcome(
                accessToken.token(), rotated.plainToken(), accessToken.expiresInSeconds());
    }

    /** 요청에 담긴 Refresh Token(과 그 기기)만 폐기한다. 다른 기기 세션은 유지된다(API 명세). */
    @Transactional
    public void logout(String plainRefreshToken) {
        refreshTokenService.revoke(plainRefreshToken);
    }
}
