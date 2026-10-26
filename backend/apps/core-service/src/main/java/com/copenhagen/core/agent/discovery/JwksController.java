package com.copenhagen.core.agent.discovery;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;

import com.copenhagen.core.agent.jwt.AgentJwtKeySet;

/**
 * mcp-server {@code JwksMcpTokenVerifier}가 조회하는 공개키 배포 엔드포인트. Agent OAuth 전용 키
 * ({@link AgentJwtKeySet}) 1개만 공개하고, 로그인 토큰의 {@code CoreAuthJwtKeySet} 키는 포함하지
 * 않는다(설계서 §4.1).
 */
@RestController
public class JwksController {

    private final AgentJwtKeySet keySet;

    public JwksController(AgentJwtKeySet keySet) {
        this.keySet = keySet;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        ECKey publicKey = new ECKey.Builder(Curve.P_256, keySet.publicKey())
                .keyID(keySet.signingKeyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .build();
        return new JWKSet(publicKey).toJSONObject();
    }
}
