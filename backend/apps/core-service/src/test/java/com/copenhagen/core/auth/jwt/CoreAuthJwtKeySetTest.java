package com.copenhagen.core.auth.jwt;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAuthJwtKeySetTest {

    @Test
    @DisplayName("설정이 없으면 키를 자동 생성하고, 그 키로 서명·검증이 round-trip된다")
    void noConfiguredKey_generatesUsableKeyPair() throws Exception {
        CoreAuthJwtKeySet keySet = new CoreAuthJwtKeySet("", "");

        assertNotNull(keySet.signingKey());
        assertNotNull(keySet.publicKey());
        assertRoundTrips(keySet);
    }

    @Test
    @DisplayName("설정된 개인키만으로 공개키를 역산하고, 그 키로 서명·검증이 round-trip된다")
    void configuredPrivateKeyOnly_derivesMatchingPublicKey() throws Exception {
        String encodedKey = generateBase64UrlPrivateKey();

        CoreAuthJwtKeySet keySet = new CoreAuthJwtKeySet(encodedKey, "configured-kid");

        assertEquals("configured-kid", keySet.signingKeyId());
        assertRoundTrips(keySet);
    }

    @Test
    @DisplayName("키 ID를 지정하지 않으면 기본값을 쓴다")
    void noKeyId_usesDefault() {
        CoreAuthJwtKeySet keySet = new CoreAuthJwtKeySet("", "core-auth-dev");

        assertEquals("core-auth-dev", keySet.signingKeyId());
    }

    private void assertRoundTrips(CoreAuthJwtKeySet keySet) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("probe").build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keySet.signingKeyId()).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new ECDSASigner(keySet.signingKey()));

        assertTrue(jwt.verify(new ECDSAVerifier(keySet.publicKey())));
    }

    private String generateBase64UrlPrivateKey() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        ECPrivateKey privateKey = (ECPrivateKey) keyPairGenerator.generateKeyPair().getPrivate();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(privateKey.getEncoded());
    }
}
