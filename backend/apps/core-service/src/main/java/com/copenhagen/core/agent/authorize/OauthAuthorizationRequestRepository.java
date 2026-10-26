package com.copenhagen.core.agent.authorize;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OauthAuthorizationRequestRepository {

    long insert(
            UUID requestId,
            long oauthClientId,
            String redirectUri,
            String requestedScopesJson,
            String state,
            String codeChallenge,
            String codeChallengeMethod,
            Instant requestExpiresAt,
            Instant now);

    Optional<OauthAuthorizationRequest> findByRequestId(UUID requestId);

    /** 인가 코드 소비(교환)의 동시성 안전을 위해 행 잠금을 건다. */
    Optional<OauthAuthorizationRequest> findByCodeHashForUpdate(String codeHash);

    /** {@code status = PENDING}인 행만 대상으로 한다(멱등한 이중 제출 방지). 영향 행 수를 반환한다. */
    int markApproved(long id, long userId, String codeHash, Instant codeExpiresAt, Instant now);

    int markDenied(long id, Instant now);

    /** {@code status = APPROVED}인 행만 대상으로 한다(코드 재사용 방지). 영향 행 수를 반환한다. */
    int markConsumed(long id, Instant now);
}
