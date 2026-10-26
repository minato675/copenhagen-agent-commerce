package com.copenhagen.core.agent.refreshtoken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** 단위 테스트용 인메모리 저장소. 행 잠금은 의미가 없으므로 {@code findByTokenHashForUpdate}는 일반 조회와 같다. */
public class FakeAgentRefreshTokenRepository implements AgentRefreshTokenRepository {

    final List<AgentRefreshToken> rows = new ArrayList<>();
    private long nextId = 1;

    @Override
    public long insert(long connectionId, String tokenHash, String scopesJson, Instant expiresAt, Instant now) {
        long id = nextId++;
        rows.add(new AgentRefreshToken(id, connectionId, tokenHash, scopesJson, now, expiresAt, null, null, now));
        return id;
    }

    @Override
    public Optional<AgentRefreshToken> findByTokenHash(String tokenHash) {
        return rows.stream().filter(row -> row.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public Optional<AgentRefreshToken> findByTokenHashForUpdate(String tokenHash) {
        return findByTokenHash(tokenHash);
    }

    @Override
    public void revokeById(long id, Instant now) {
        replace(row -> row.id() == id && row.revokedAt() == null, now);
    }

    @Override
    public int revokeAllActiveByConnectionId(long connectionId, Instant now) {
        return replace(row -> row.connectionId() == connectionId && row.revokedAt() == null, now);
    }

    private int replace(Predicate<AgentRefreshToken> match, Instant revokedAt) {
        int affected = 0;
        for (int i = 0; i < rows.size(); i++) {
            AgentRefreshToken row = rows.get(i);
            if (!match.test(row)) {
                continue;
            }
            rows.set(i, new AgentRefreshToken(
                    row.id(), row.connectionId(), row.tokenHash(), row.scopesJson(),
                    row.issuedAt(), row.expiresAt(), revokedAt, revokedAt, row.createdAt()));
            affected++;
        }
        return affected;
    }

    AgentRefreshToken seed(
            long connectionId, String tokenHash, String scopesJson,
            Instant issuedAt, Instant expiresAt, Instant revokedAt) {
        long id = nextId++;
        AgentRefreshToken row = new AgentRefreshToken(
                id, connectionId, tokenHash, scopesJson, issuedAt, expiresAt, revokedAt, null, issuedAt);
        rows.add(row);
        return row;
    }

    Optional<AgentRefreshToken> findById(long id) {
        return rows.stream().filter(row -> row.id() == id).findFirst();
    }
}
