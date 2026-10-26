package com.copenhagen.core.agent.connection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeAgentConnectionRepository implements AgentConnectionRepository {

    final List<AgentConnection> rows = new ArrayList<>();
    private long nextId = 1;

    @Override
    public Optional<AgentConnection> findActiveByUserIdAndOauthClientId(long userId, long oauthClientId) {
        return rows.stream()
                .filter(row -> row.userId() == userId && row.oauthClientId() == oauthClientId
                        && row.revokedAt() == null)
                .findFirst();
    }

    @Override
    public Optional<AgentConnection> findActiveByPublicIdAndUserId(UUID publicId, long userId) {
        return rows.stream()
                .filter(row -> row.publicId().equals(publicId) && row.userId() == userId && row.revokedAt() == null)
                .findFirst();
    }

    @Override
    public Optional<AgentConnection> findById(long id) {
        return rows.stream().filter(row -> row.id() == id).findFirst();
    }

    @Override
    public long insert(UUID publicId, long userId, long oauthClientId, String grantedScopesJson, Instant now) {
        long id = nextId++;
        rows.add(new AgentConnection(
                id, publicId, userId, oauthClientId, grantedScopesJson, now, null, null, now, now));
        return id;
    }

    @Override
    public void revoke(long id, Instant now) {
        for (int i = 0; i < rows.size(); i++) {
            AgentConnection row = rows.get(i);
            if (row.id() != id || row.revokedAt() != null) {
                continue;
            }
            rows.set(i, new AgentConnection(
                    row.id(), row.publicId(), row.userId(), row.oauthClientId(), row.grantedScopesJson(),
                    row.connectedAt(), row.lastUsedAt(), now, row.createdAt(), now));
        }
    }

    @Override
    public List<AgentConnectionSummary> findActiveSummariesByUserId(long userId) {
        return rows.stream()
                .filter(row -> row.userId() == userId && row.revokedAt() == null)
                .sorted(Comparator.comparing(AgentConnection::connectedAt).reversed())
                .map(row -> new AgentConnectionSummary(
                        row.publicId(), "client-" + row.oauthClientId(), null,
                        row.grantedScopesJson(), row.connectedAt(), row.lastUsedAt()))
                .toList();
    }
}
