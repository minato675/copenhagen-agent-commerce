package com.copenhagen.core.agent.refreshtoken;

import java.time.Instant;

public record AgentRefreshToken(
        long id,
        long connectionId,
        String tokenHash,
        String scopesJson,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant lastUsedAt,
        Instant createdAt) {

    @Override
    public String toString() {
        return "AgentRefreshToken["
                + "id=" + id
                + ", connectionId=" + connectionId
                + ", issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt
                + ", revokedAt=" + revokedAt
                + ", lastUsedAt=" + lastUsedAt
                + ", createdAt=" + createdAt
                + ']';
    }
}
