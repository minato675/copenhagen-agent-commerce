package com.copenhagen.core.auth.refreshtoken;

import java.time.Instant;

public record RefreshToken(
        long id,
        long userId,
        String tokenHash,
        String deviceId,
        String deviceName,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant lastUsedAt,
        Instant createdAt) {

    @Override
    public String toString() {
        return "RefreshToken["
                + "id=" + id
                + ", userId=" + userId
                + ", deviceId='" + deviceId + '\''
                + ", deviceName='" + deviceName + '\''
                + ", issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt
                + ", revokedAt=" + revokedAt
                + ", lastUsedAt=" + lastUsedAt
                + ", createdAt=" + createdAt
                + ']';
    }
}
