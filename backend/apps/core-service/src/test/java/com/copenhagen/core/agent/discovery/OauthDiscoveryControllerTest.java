package com.copenhagen.core.agent.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.agent.jwt.AgentOauthProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OauthDiscoveryControllerTest {

    @Test
    @DisplayName("메타데이터는 issuer 기준으로 계산된 엔드포인트와 고정 scope 카탈로그를 담는다")
    void metadata_reflectsIssuerAndScopeCatalog() {
        AgentOauthProperties properties =
                new AgentOauthProperties("https://core.example.com", "https://core.example.com/mcp");
        OauthDiscoveryController controller = new OauthDiscoveryController(properties);

        OauthDiscoveryController.Metadata metadata = controller.metadata();

        assertEquals("https://core.example.com", metadata.issuer());
        assertTrue(metadata.authorizationResponseIssParameterSupported());
        assertEquals("https://core.example.com/oauth/authorize", metadata.authorizationEndpoint());
        assertEquals("https://core.example.com/oauth/token", metadata.tokenEndpoint());
        assertEquals("https://core.example.com/.well-known/jwks.json", metadata.jwksUri());
        assertTrue(metadata.grantTypesSupported().containsAll(
                java.util.List.of("authorization_code", "refresh_token", "client_credentials")));
        assertTrue(metadata.scopesSupported().containsAll(
                java.util.List.of("catalog:read", "cart:read", "cart:write", "purchase:handoff")));
    }
}
