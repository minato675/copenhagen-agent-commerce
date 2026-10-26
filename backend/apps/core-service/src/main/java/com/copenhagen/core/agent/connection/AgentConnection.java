package com.copenhagen.core.agent.connection;

import java.time.Instant;
import java.util.UUID;

public record AgentConnection(
        long id,
        UUID publicId,
        long userId,
        long oauthClientId,
        String grantedScopesJson,
        Instant connectedAt,
        Instant lastUsedAt,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt) {
}
