package com.copenhagen.mcp.auth;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/** 인증 필터가 생성해 SDK transport context로 전달하는 요청별 사용자 문맥. */
public record McpIdentity(String publicUserRef, String connectionRef, Set<String> scopes, Instant expiresAt,
        String agentAccessToken) {

    public static final String CONTEXT_KEY = McpIdentity.class.getName();

    public McpIdentity(String publicUserRef, String connectionRef, Set<String> scopes, Instant expiresAt) {
        this(publicUserRef, connectionRef, scopes, expiresAt, null);
    }

    public McpIdentity {
        if (publicUserRef == null || publicUserRef.isBlank() || connectionRef == null || connectionRef.isBlank()
                || expiresAt == null) {
            throw new McpAuthException(McpAuthError.INVALID_TOKEN);
        }
        scopes = Set.copyOf(scopes);
    }

    public static McpIdentity from(McpSyncServerExchange exchange) {
        if (exchange == null || !(exchange.transportContext().get(CONTEXT_KEY) instanceof McpIdentity identity)) {
            throw new McpAuthException(McpAuthError.AUTHENTICATION_REQUIRED);
        }
        return identity;
    }

    public void requireScopes(Set<String> required) {
        if (!scopes.containsAll(required)) {
            // 추가 동의가 이미 승인한 권한을 축소시키지 않도록 현재 권한도 보존한다.
            throw new McpAuthException(McpAuthError.INSUFFICIENT_SCOPE,
                    Stream.concat(scopes.stream(), required.stream()).collect(Collectors.toUnmodifiableSet()));
        }
    }

    /** ownerPublicUserRef는 Tool 입력이 아니라 Core에서 조회·검증한 자원 소유자여야 한다. */
    public void requireOwner(String ownerPublicUserRef) {
        if (!publicUserRef.equals(ownerPublicUserRef)) {
            throw new McpAuthException(McpAuthError.ACCESS_DENIED);
        }
    }

    @Override
    public String toString() {
        return "McpIdentity[redacted]";
    }
}
