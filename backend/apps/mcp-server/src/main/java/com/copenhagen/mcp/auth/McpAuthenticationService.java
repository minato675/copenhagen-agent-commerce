package com.copenhagen.mcp.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

public final class McpAuthenticationService {

    private final McpAuthProperties properties;
    private final Supplier<McpTokenVerifier> verifier;
    private final Clock clock;

    public McpAuthenticationService(McpAuthProperties properties, Supplier<McpTokenVerifier> verifier, Clock clock) {
        this.properties = properties;
        this.verifier = verifier;
        this.clock = clock;
    }

    public McpIdentity authenticate(String accessToken) {
        if (!properties.configured()) {
            throw new McpAuthException(McpAuthError.AUTH_NOT_CONFIGURED);
        }
        VerifiedMcpToken token;
        try {
            McpTokenVerifier provider = verifier.get();
            if (provider == null) {
                throw new McpAuthException(McpAuthError.AUTH_NOT_CONFIGURED);
            }
            // 매 요청 재검증하며 토큰·연결 상태를 세션 인증으로 대체하거나 캐시하지 않는다.
            token = provider.verify(accessToken);
        } catch (McpAuthException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 제공자의 예외 메시지나 원문 토큰을 반환·기록하지 않는다.
            throw new McpAuthException(McpAuthError.AUTH_PROVIDER_UNAVAILABLE);
        }
        Instant now = clock.instant();
        if (token == null || !token.active() || !properties.issuer().toString().equals(token.issuer())
                || !token.audiences().contains(properties.resource().toString())
                || token.expiresAt() == null || !now.isBefore(token.expiresAt())
                || (token.notBefore() != null && now.isBefore(token.notBefore()))) {
            throw new McpAuthException(McpAuthError.INVALID_TOKEN);
        }
        if (!token.connected()) {
            throw new McpAuthException(McpAuthError.ACCOUNT_NOT_CONNECTED);
        }
        McpIdentity identity = new McpIdentity(token.publicUserRef(), token.connectionRef(), token.scopes(),
                token.expiresAt(), accessToken);
        identity.requireScopes(properties.requiredScopes());
        return identity;
    }
}
