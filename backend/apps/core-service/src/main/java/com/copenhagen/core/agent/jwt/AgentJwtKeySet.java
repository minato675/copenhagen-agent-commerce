package com.copenhagen.core.agent.jwt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent OAuth(이슈 S15P21A207-337) 전용 서명 키. {@link com.copenhagen.core.auth.jwt.CoreAuthJwtKeySet}과
 * 알고리즘(EC secp256r1/ES256)은 같지만 별도 키를 쓴다 — 로그인 토큰의 키는 pay-service에만 정적 JWK로
 * 공유되는 내부 전용 키라 공개 JWKS(mcp-server가 조회하는 {@code /.well-known/jwks.json}) 노출 대상이
 * 아니기 때문이다. 두 키를 분리해 로테이션·노출 범위를 독립시킨다.
 */
@Component
public class AgentJwtKeySet {

    private static final String KEY_ALGORITHM = "EC";
    private static final String CURVE_NAME = "secp256r1";
    private static final String PRIVATE_KEY_ERROR = "AGENT_OAUTH_JWT_PRIVATE_KEY must be a Base64URL PKCS#8 EC key";

    private final ECPrivateKey signingKey;
    private final ECPublicKey publicKey;
    private final String signingKeyId;

    public AgentJwtKeySet(
            @Value("${agent.oauth.jwt.signing-key:}") String signingPrivateKey,
            @Value("${agent.oauth.jwt.key-id:agent-oauth-dev}") String keyId) {
        if (signingPrivateKey == null || signingPrivateKey.isBlank()) {
            KeyPair keyPair = generateKeyPair();
            this.signingKey = (ECPrivateKey) keyPair.getPrivate();
            this.publicKey = (ECPublicKey) keyPair.getPublic();
        } else {
            this.signingKey = decodePrivateKey(signingPrivateKey);
            this.publicKey = derivePublicKey(signingKey);
        }
        this.signingKeyId = keyId;
    }

    public ECPrivateKey signingKey() {
        return signingKey;
    }

    public ECPublicKey publicKey() {
        return publicKey;
    }

    public String signingKeyId() {
        return signingKeyId;
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyPairGenerator.initialize(new ECGenParameterSpec(CURVE_NAME));
            return keyPairGenerator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate Agent OAuth signing key", exception);
        }
    }

    private ECPrivateKey decodePrivateKey(String configuredKey) {
        try {
            byte[] encodedKey = Base64.getUrlDecoder().decode(configuredKey.getBytes(StandardCharsets.US_ASCII));
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            if (!(keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encodedKey)) instanceof ECPrivateKey ecKey)) {
                throw new IllegalArgumentException(PRIVATE_KEY_ERROR);
            }
            return ecKey;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(PRIVATE_KEY_ERROR, exception);
        }
    }

    private ECPublicKey derivePublicKey(ECPrivateKey privateKey) {
        try {
            ECNamedCurveParameterSpec curve = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
            ECPoint point = curve.getG().multiply(privateKey.getS()).normalize();
            java.security.spec.ECPoint publicPoint = new java.security.spec.ECPoint(
                    point.getAffineXCoord().toBigInteger(),
                    point.getAffineYCoord().toBigInteger());
            return (ECPublicKey) KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new ECPublicKeySpec(publicPoint, privateKey.getParams()));
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalStateException(PRIVATE_KEY_ERROR, exception);
        }
    }
}
