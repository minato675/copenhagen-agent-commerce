package com.copenhagen.core.agent.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent OAuth 인가 서버 자신을 가리키는 값들. {@code core.pii.*}와 같은 이유로 기본값을 두지 않는다 —
 * 운영에서 미설정 시 기동 실패가 의도된 동작이다. 로컬·테스트 프로필만 개발 전용 값을 갖는다.
 */
@Component
public class AgentOauthProperties {

    private final String issuer;
    private final String mcpResourceAudience;

    public AgentOauthProperties(
            @Value("${agent.oauth.issuer}") String issuer,
            @Value("${agent.oauth.mcp-resource-audience}") String mcpResourceAudience) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("agent.oauth.issuer must be configured");
        }
        if (mcpResourceAudience == null || mcpResourceAudience.isBlank()) {
            throw new IllegalStateException("agent.oauth.mcp-resource-audience must be configured");
        }
        this.issuer = issuer;
        this.mcpResourceAudience = mcpResourceAudience;
    }

    public String issuer() {
        return issuer;
    }

    public String mcpResourceAudience() {
        return mcpResourceAudience;
    }

    public String authorizationEndpoint() {
        return issuer + "/oauth/authorize";
    }

    public String tokenEndpoint() {
        return issuer + "/oauth/token";
    }

    public String jwksUri() {
        return issuer + "/.well-known/jwks.json";
    }
}
