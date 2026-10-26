package com.copenhagen.core.agent.authorize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeOauthAuthorizationRequestRepository implements OauthAuthorizationRequestRepository {

    final List<OauthAuthorizationRequest> rows = new ArrayList<>();
    private long nextId = 1;

    @Override
    public long insert(
            UUID requestId, long oauthClientId, String redirectUri, String requestedScopesJson, String state,
            String codeChallenge, String codeChallengeMethod, Instant requestExpiresAt, Instant now) {
        long id = nextId++;
        rows.add(new OauthAuthorizationRequest(
                id, requestId, oauthClientId, redirectUri, requestedScopesJson, state, codeChallenge,
                codeChallengeMethod, OauthAuthorizationRequest.STATUS_PENDING, null, null, null,
                requestExpiresAt, now, now));
        return id;
    }

    @Override
    public Optional<OauthAuthorizationRequest> findByRequestId(UUID requestId) {
        return rows.stream().filter(row -> row.requestId().equals(requestId)).findFirst();
    }

    @Override
    public Optional<OauthAuthorizationRequest> findByCodeHashForUpdate(String codeHash) {
        return rows.stream().filter(row -> codeHash.equals(row.codeHash())).findFirst();
    }

    @Override
    public int markApproved(long id, long userId, String codeHash, Instant codeExpiresAt, Instant now) {
        return replaceIfStatus(id, OauthAuthorizationRequest.STATUS_PENDING, row -> new OauthAuthorizationRequest(
                row.id(), row.requestId(), row.oauthClientId(), row.redirectUri(), row.requestedScopesJson(),
                row.state(), row.codeChallenge(), row.codeChallengeMethod(), OauthAuthorizationRequest.STATUS_APPROVED,
                userId, codeHash, codeExpiresAt, row.requestExpiresAt(), row.createdAt(), now));
    }

    @Override
    public int markDenied(long id, Instant now) {
        return replaceIfStatus(id, OauthAuthorizationRequest.STATUS_PENDING, row -> new OauthAuthorizationRequest(
                row.id(), row.requestId(), row.oauthClientId(), row.redirectUri(), row.requestedScopesJson(),
                row.state(), row.codeChallenge(), row.codeChallengeMethod(), OauthAuthorizationRequest.STATUS_DENIED,
                row.userId(), row.codeHash(), row.codeExpiresAt(), row.requestExpiresAt(), row.createdAt(), now));
    }

    @Override
    public int markConsumed(long id, Instant now) {
        return replaceIfStatus(id, OauthAuthorizationRequest.STATUS_APPROVED, row -> new OauthAuthorizationRequest(
                row.id(), row.requestId(), row.oauthClientId(), row.redirectUri(), row.requestedScopesJson(),
                row.state(), row.codeChallenge(), row.codeChallengeMethod(), OauthAuthorizationRequest.STATUS_CONSUMED,
                row.userId(), row.codeHash(), row.codeExpiresAt(), row.requestExpiresAt(), row.createdAt(), now));
    }

    private int replaceIfStatus(
            long id, String requiredStatus, java.util.function.UnaryOperator<OauthAuthorizationRequest> mapper) {
        for (int i = 0; i < rows.size(); i++) {
            OauthAuthorizationRequest row = rows.get(i);
            if (row.id() != id || !requiredStatus.equals(row.status())) {
                continue;
            }
            rows.set(i, mapper.apply(row));
            return 1;
        }
        return 0;
    }
}
