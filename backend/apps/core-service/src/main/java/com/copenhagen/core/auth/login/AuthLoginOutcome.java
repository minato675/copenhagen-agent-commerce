package com.copenhagen.core.auth.login;

public record AuthLoginOutcome(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        LoginResult loginResult) {

    @Override
    public String toString() {
        return "AuthLoginOutcome[accessToken=<redacted>, refreshToken=<redacted>, expiresInSeconds="
                + expiresInSeconds + ", loginResult=" + loginResult + ']';
    }
}
