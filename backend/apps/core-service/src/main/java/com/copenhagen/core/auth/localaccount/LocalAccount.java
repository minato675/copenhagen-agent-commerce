package com.copenhagen.core.auth.localaccount;

import java.time.Instant;

public record LocalAccount(
        long id,
        long userId,
        String loginId,
        String passwordHash,
        int failedLoginCount,
        Instant lastFailedLoginAt,
        Instant lockedUntil,
        Instant createdAt,
        Instant updatedAt) {

    @Override
    public String toString() {
        return "LocalAccount["
                + "id=" + id
                + ", userId=" + userId
                + ", loginId='" + loginId + '\''
                + ", failedLoginCount=" + failedLoginCount
                + ", lastFailedLoginAt=" + lastFailedLoginAt
                + ", lockedUntil=" + lockedUntil
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + ']';
    }
}
