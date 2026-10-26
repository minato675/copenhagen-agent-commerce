package com.copenhagen.core.agent.token;

import org.springframework.http.HttpStatus;

/** OAuth 표준 토큰 응답/오류 바디({@code error}/{@code error_description})로 그대로 이어진다. */
public sealed interface TokenResult {

    record Success(String accessToken, String refreshToken, long expiresIn, String scope) implements TokenResult {
    }

    record Error(HttpStatus httpStatus, String error, String description) implements TokenResult {
    }
}
