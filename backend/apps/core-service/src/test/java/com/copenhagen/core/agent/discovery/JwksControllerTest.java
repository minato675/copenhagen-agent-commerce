package com.copenhagen.core.agent.discovery;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

import com.copenhagen.core.agent.jwt.AgentJwtKeySet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JwksControllerTest {

    @Test
    @DisplayName("JWKS 응답은 mcp-server JwksMcpTokenVerifier가 그대로 파싱 가능한 형태다")
    void jwks_roundTripsThroughNimbusJwkSet() throws Exception {
        AgentJwtKeySet keySet = new AgentJwtKeySet("", "agent-oauth-test");
        JwksController controller = new JwksController(keySet);

        Map<String, Object> body = controller.jwks();

        JWKSet parsed = JWKSet.parse(body.toString());
        JWK key = parsed.getKeyByKeyId("agent-oauth-test");
        assertFalse(key.isPrivate());
        assertEquals("EC", key.getKeyType().getValue());
    }
}
