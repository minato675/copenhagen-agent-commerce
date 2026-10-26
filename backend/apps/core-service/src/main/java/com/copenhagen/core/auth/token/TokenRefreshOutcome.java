package com.copenhagen.core.auth.token;

public record TokenRefreshOutcome(
        String accessToken,
        String refreshToken,
        long expiresInSeconds) {

    @Override
    public String toString() {
        return "TokenRefreshOutcome[accessToken=<redacted>, refreshToken=<redacted>, expiresInSeconds="
                + expiresInSeconds + ']';
    }
}
