package com.copenhagen.core.agent.connection;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentConnectionRepository {

    Optional<AgentConnection> findActiveByUserIdAndOauthClientId(long userId, long oauthClientId);

    Optional<AgentConnection> findActiveByPublicIdAndUserId(UUID publicId, long userId);

    Optional<AgentConnection> findById(long id);

    long insert(UUID publicId, long userId, long oauthClientId, String grantedScopesJson, Instant now);

    void revoke(long id, Instant now);

    List<AgentConnectionSummary> findActiveSummariesByUserId(long userId);
}
