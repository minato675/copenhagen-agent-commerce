package com.copenhagen.core.auth.refreshtoken;

import java.time.Instant;

public record IssuedRefreshToken(String plainToken, Instant expiresAt) {

    @Override
    public String toString() {
        return "IssuedRefreshToken[plainToken=<redacted>, expiresAt=" + expiresAt + ']';
    }
}
