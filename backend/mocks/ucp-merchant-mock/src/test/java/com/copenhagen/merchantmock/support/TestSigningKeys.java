package com.copenhagen.merchantmock.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import com.copenhagen.merchantmock.security.SigningKeyProvider;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;

/**
 * 테스트용 ES256 키 한 쌍. 리포에 개인키를 두지 않으므로 테스트마다 새로 만든다.
 *
 * <p>{@link SigningKeyProvider} 가 읽는 모양(PKCS#8 PEM + 공개 JWK JSON)으로 설정을 만들어 준다.
 * 서명·검증 테스트는 이 키로 서명하고 같은 키의 공개 절반으로 검증한다.
 */
public final class TestSigningKeys {

    public static final String KID = "merchant-a-es256-test";

    private TestSigningKeys() {
    }

    /** 개인키가 든 EC JWK. */
    public static ECKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            return new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic())
                    .privateKey((ECPrivateKey) pair.getPrivate())
                    .keyID(KID)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("테스트 키를 만들 수 없다", exception);
        }
    }

    /** 이 키로 서명하도록 채운 merchant.* 설정. */
    public static MerchantProperties propertiesSigningWith(ECKey key) {
        return MerchantPropertiesFixture.withSigning(new MerchantProperties.Signing(
                key.getKeyID(), "ES256", "", toPkcs8Pem(key), key.toPublicJWK().toJSONString()));
    }

    public static SigningKeyProvider providerSigningWith(ECKey key) {
        return new SigningKeyProvider(propertiesSigningWith(key));
    }

    private static String toPkcs8Pem(ECKey key) {
        try {
            String body = Base64.getMimeEncoder(64, "\n".getBytes())
                    .encodeToString(key.toECPrivateKey().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception exception) {
            throw new IllegalStateException("PEM 변환에 실패했다", exception);
        }
    }
}
