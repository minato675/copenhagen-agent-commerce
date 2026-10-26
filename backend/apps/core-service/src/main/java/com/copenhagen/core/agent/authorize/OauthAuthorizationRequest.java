package com.copenhagen.core.agent.authorize;

import java.time.Instant;
import java.util.UUID;

public record OauthAuthorizationRequest(
        long id,
        UUID requestId,
        long oauthClientId,
        String redirectUri,
        String requestedScopesJson,
        String state,
        String codeChallenge,
        String codeChallengeMethod,
        String status,
        Long userId,
        String codeHash,
        Instant codeExpiresAt,
        Instant requestExpiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_DENIED = "DENIED";
    public static final String STATUS_CONSUMED = "CONSUMED";

    public boolean isExpired(Instant now) {
        return requestExpiresAt.isBefore(now);
    }
}
