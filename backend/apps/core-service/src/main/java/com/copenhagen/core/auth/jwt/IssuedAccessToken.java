package com.copenhagen.core.auth.jwt;

public record IssuedAccessToken(String token, long expiresInSeconds) {

    @Override
    public String toString() {
        return "IssuedAccessToken[token=<redacted>, expiresInSeconds=" + expiresInSeconds + ']';
    }
}
