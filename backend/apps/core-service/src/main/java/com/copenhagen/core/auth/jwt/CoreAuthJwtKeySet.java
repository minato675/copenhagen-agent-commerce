package com.copenhagen.core.auth.jwt;

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
 * 로그인 Access Token 서명 키 1개만 다루는 축소판 키셋이다({@code pay-service}의 {@code MandateKeySet}과 달리
 * 키 로테이션·복수 검증 키를 지원하지 않는다 — 공개키 배포·로테이션은 D-14로 이번 범위 밖).
 * {@code CORE_AUTH_JWT_PRIVATE_KEY}가 없으면 개발 편의를 위해 매 기동마다 새 키 쌍을 생성한다(재기동 시
 * 이전에 발급한 토큰은 더 이상 검증되지 않는다 — 운영에서는 반드시 키를 설정해야 한다).
 */
@Component
public class CoreAuthJwtKeySet {

    private static final String KEY_ALGORITHM = "EC";
    private static final String CURVE_NAME = "secp256r1";
    private static final String PRIVATE_KEY_ERROR = "CORE_AUTH_JWT_PRIVATE_KEY must be a Base64URL PKCS#8 EC key";

    private final ECPrivateKey signingKey;
    private final ECPublicKey publicKey;
    private final String signingKeyId;

    public CoreAuthJwtKeySet(
            @Value("${core.auth.jwt.signing-key:}") String signingPrivateKey,
            @Value("${core.auth.jwt.key-id:core-auth-dev}") String keyId) {
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
            throw new IllegalStateException("Unable to generate Core auth signing key", exception);
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

    /** EC 개인키의 스칼라 값으로 곡선 위 공개점을 직접 계산한다({@code MandateKeySet.derivePublicKey()}와 동일). */
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
