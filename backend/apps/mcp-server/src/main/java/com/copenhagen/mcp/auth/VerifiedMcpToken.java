package com.copenhagen.mcp.auth;

import java.time.Instant;
import java.util.Set;

/** 원문 토큰·내부 DB ID를 포함하지 않는 서버 검증 결과. 외부 API DTO가 아니다. */
public record VerifiedMcpToken(
        boolean active,
        String issuer,
        Set<String> audiences,
        Instant expiresAt,
        Instant notBefore,
        String publicUserRef,
        String connectionRef,
        boolean connected,
        Set<String> scopes) {

    public VerifiedMcpToken {
        audiences = Set.copyOf(audiences);
        scopes = Set.copyOf(scopes);
    }

    @Override
    public String toString() {
        return "VerifiedMcpToken[redacted]";
    }
}
