package com.copenhagen.mcp;

import com.copenhagen.mcp.auth.McpAuthError;
import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpTokenVerifier;
import com.copenhagen.mcp.auth.VerifiedMcpToken;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 로컬 테스트 전용 불투명 토큰 검증 대역. 운영 OAuth 구현이 아니다. */
public final class FakeMcpTokenVerifier implements McpTokenVerifier {

    public static final String ISSUER = "https://auth.example.test";
    public static final String RESOURCE = "https://mcp.example.test/mcp";
    public static final String DEFAULT_TOKEN = UUID.randomUUID().toString();

    private final Map<String, VerifiedMcpToken> tokens = new ConcurrentHashMap<>();

    public FakeMcpTokenVerifier() {
        tokens.put(DEFAULT_TOKEN, connected("user-a", "connection-a", Set.of("mcp:access")));
    }

    public String issue(VerifiedMcpToken claims) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, claims);
        return token;
    }

    public void revoke(String token) {
        tokens.remove(token);
    }

    public void replace(String token, VerifiedMcpToken claims) {
        tokens.put(token, claims);
    }

    public static VerifiedMcpToken connected(String user, String connection, Set<String> scopes) {
        return new VerifiedMcpToken(true, ISSUER, Set.of(RESOURCE), Instant.now().plusSeconds(600),
                Instant.now().minusSeconds(10), user, connection, true, scopes);
    }

    @Override
    public VerifiedMcpToken verify(String accessToken) {
        VerifiedMcpToken token = tokens.get(accessToken);
        if (token == null) {
            throw new McpAuthException(McpAuthError.INVALID_TOKEN);
        }
        return token;
    }
}
