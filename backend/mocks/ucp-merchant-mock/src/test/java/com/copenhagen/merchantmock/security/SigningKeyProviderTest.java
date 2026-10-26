package com.copenhagen.merchantmock.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;

import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서명 키 로딩 테스트.
 *
 * <p>개인키를 테스트 리소스로 커밋하지 않는다. 필요한 키 쌍은 테스트가 실행될 때마다 새로 만든다.
 */
class SigningKeyProviderTest {

    private static final String KID = "merchant-a-es256-1";

    @Test
    @DisplayName("개인키가 없으면 임시 키 쌍을 만들고 설정된 공개키는 쓰지 않는다")
    void load_noPrivateKey_generatesEphemeralPairAndIgnoresConfiguredPublicKey() {
        // given & when
        SigningKeyProvider provider = new SigningKeyProvider(MerchantPropertiesFixture.defaults());

        // then
        assertTrue(provider.isEphemeral());
        assertTrue(provider.canSign());
        Map<String, Object> jwk = provider.publicJwk().orElseThrow();
        assertEquals(KID, jwk.get("kid"), "kid 는 설정값 그대로다");
        assertEquals("sig", jwk.get("use"));
        assertEquals("ES256", jwk.get("alg"));
        assertNotEquals("Kv4fWw_TEzEQ2EOMOgsbIyH9314FhRdQknrvaPY5Q3Q", jwk.get("x"),
                "서명할 수 없는 설정 공개키를 Profile 에 실으면 거짓말이다");
        assertEquals(KID, provider.requireSigningKey().getKeyID());
    }

    @Test
    @DisplayName("공개키까지 비어 있어도 임시 키가 있어 keys 는 비지 않는다")
    void load_blankPublicKey_stillHasEphemeralJwk() {
        // given
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(KID, "ES256", "", "", ""));

        // when
        SigningKeyProvider provider = new SigningKeyProvider(properties);

        // then
        assertTrue(provider.publicJwk().isPresent());
        assertTrue(provider.isEphemeral());
    }

    @Test
    @DisplayName("임시 키는 기동마다 다르다")
    void load_noPrivateKeyTwice_generatesDifferentKeys() {
        // given & when
        SigningKeyProvider first = new SigningKeyProvider(MerchantPropertiesFixture.defaults());
        SigningKeyProvider second = new SigningKeyProvider(MerchantPropertiesFixture.defaults());

        // then
        assertNotEquals(first.publicJwk().orElseThrow().get("x"), second.publicJwk().orElseThrow().get("x"));
    }

    @Test
    @DisplayName("공개키 JWK 에 kid 와 곡선이 실린다")
    void publicJwk_defaults_carriesKidAndCurve() {
        // given
        SigningKeyProvider provider = new SigningKeyProvider(MerchantPropertiesFixture.defaults());

        // when
        Map<String, Object> jwk = provider.publicJwk().orElseThrow();

        // then
        assertEquals("EC", jwk.get("kty"));
        assertEquals("P-256", jwk.get("crv"));
        assertEquals(KID, jwk.get("kid"));
        assertFalse(jwk.containsKey("d"), "공개키에 개인키 성분이 실리면 안 된다");
    }

    @Test
    @DisplayName("짝이 맞는 개인키를 읽으면 서명할 수 있다")
    void load_matchingKeyPair_enablesSigning() throws Exception {
        // given
        KeyPair pair = generateP256();
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(KID, "ES256", "", toPkcs8Pem(pair), publicJwkOf(pair)));

        // when
        SigningKeyProvider provider = new SigningKeyProvider(properties);

        // then
        assertTrue(provider.canSign());
        assertFalse(provider.isEphemeral());
        assertEquals(KID, provider.requireSigningKey().getKeyID());
    }

    @Test
    @DisplayName("짝이 안 맞는 개인키는 기동에서 잡는다")
    void load_mismatchedKeyPair_throwsOnStartup() throws Exception {
        // given
        String otherPem = toPkcs8Pem(generateP256());
        String jwk = publicJwkOf(generateP256());
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(KID, "ES256", "", otherPem, jwk));

        // when & then
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> new SigningKeyProvider(properties));

        assertTrue(thrown.getMessage().contains("같은 쌍이 아니다"), thrown.getMessage());
    }

    @Test
    @DisplayName("개인키만 있으면 기동에서 잡는다")
    void load_privateKeyWithoutPublicKey_throwsOnStartup() throws Exception {
        // given
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(KID, "ES256", "", toPkcs8Pem(generateP256()), ""));

        // when & then
        assertThrows(IllegalStateException.class, () -> new SigningKeyProvider(properties));
    }

    @Test
    @DisplayName("없는 경로를 주면 기동에서 잡는다")
    void load_missingKeyFile_throwsOnStartup() {
        // given
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing(
                        KID, "ES256", "/does/not/exist.pem", "", MerchantPropertiesFixture.PUBLIC_JWK));

        // when & then
        assertThrows(IllegalStateException.class, () -> new SigningKeyProvider(properties));
    }

    private static KeyPair generateP256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String toPkcs8Pem(KeyPair pair) {
        ECPrivateKey privateKey = (ECPrivateKey) pair.getPrivate();
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(privateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    private static String publicJwkOf(KeyPair pair) {
        ECKey key = new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic()).keyID(KID).build();
        return key.toJSONString();
    }
}
