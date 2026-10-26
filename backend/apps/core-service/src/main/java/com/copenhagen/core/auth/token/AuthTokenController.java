package com.copenhagen.core.auth.token;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refresh Token 수명주기 API(구현순서 6·7). 두 엔드포인트 모두 Access Token을 요구하지 않는다 — Access Token이
 * 이미 만료된 상태에서도 갱신·로그아웃이 되어야 하므로, Refresh Token 소유 자체가 인증 수단이다(API 명세의
 * Request Header가 비어 있는 이유).
 */
@RestController
@RequestMapping("/api/core/v1/auth")
public class AuthTokenController {

    private final AuthTokenService authTokenService;

    public AuthTokenController(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    @PostMapping("/token/refresh")
    public TokenRefreshResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return TokenRefreshResponse.from(authTokenService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authTokenService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    public record TokenRefreshRequest(@NotBlank String refreshToken) {

        @Override
        public String toString() {
            return "TokenRefreshRequest[refreshToken=<redacted>]";
        }
    }

    public record LogoutRequest(@NotBlank String refreshToken) {

        @Override
        public String toString() {
            return "LogoutRequest[refreshToken=<redacted>]";
        }
    }

    public record TokenRefreshResponse(
            String accessToken,
            String refreshToken,
            long expiresIn) {

        static TokenRefreshResponse from(TokenRefreshOutcome outcome) {
            return new TokenRefreshResponse(
                    outcome.accessToken(), outcome.refreshToken(), outcome.expiresInSeconds());
        }

        @Override
        public String toString() {
            return "TokenRefreshResponse[accessToken=<redacted>, refreshToken=<redacted>, expiresIn="
                    + expiresIn + ']';
        }
    }
}
