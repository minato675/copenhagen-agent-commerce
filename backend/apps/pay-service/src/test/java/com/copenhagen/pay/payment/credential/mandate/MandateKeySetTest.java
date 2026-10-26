package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.payment.authorization.AuthorizationSessionFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import tools.jackson.databind.ObjectMapper;
import org.springframework.mock.env.MockEnvironment;

class MandateKeySetTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void configuredSigningKeyProducesVerifiablePublicProfileKey() throws Exception {
        KeyMaterial active = keyMaterial("pay-signing-key-1");
        MandateProperties properties = properties(active, "[]", true);
        MandateKeySet keySet = new MandateKeySet(
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
        MandateSigningService signingService = new MandateSigningService(properties, keySet);

        SignedJWT mandate = SignedJWT.parse(signingService.createCheckoutMandate(
                session(),
                session().items().getFirst(),
                "mandate-001",
                NOW));

        ECKey profileKey = keySet.publicKeys().getFirst();
        assertEquals("pay-signing-key-1", profileKey.getKeyID());
        assertFalse(profileKey.isPrivate());
        assertTrue(mandate.verify(new ECDSAVerifier(profileKey.toECPublicKey())));
    }

    @Test
    void previousKeyIsPublishedAndExpiresAfterVerificationGracePeriod() throws Exception {
        KeyMaterial active = keyMaterial("pay-signing-key-1");
        KeyMaterial previous = keyMaterial("pay-signing-key-0");
        String previousConfig = new ObjectMapper().writeValueAsString(List.of(
                Map.of(
                        "kid",
                        previous.key().getKeyID(),
                        "verifyUntil",
                        NOW.plusSeconds(60).toString(),
                        "jwk",
                        previous.key().toPublicJWK().toJSONObject())));

        MandateKeySet keySet = new MandateKeySet(
                properties(active, previousConfig, true),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());

        assertEquals(
                List.of("pay-signing-key-1", "pay-signing-key-0"),
                keySet.publicKeys().stream().map(ECKey::getKeyID).toList());
        assertTrue(keySet.findVerificationKey("pay-signing-key-0").isPresent());

        MandateKeySet expiredKeySet = new MandateKeySet(
                properties(active, previousConfig, true),
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC),
                new ObjectMapper());
        assertEquals(1, expiredKeySet.publicKeys().size());
        assertTrue(expiredKeySet.findVerificationKey("pay-signing-key-0").isEmpty());
    }

    @Test
    void duplicateKidsAndInvalidConfigurationFailAtStartup() throws Exception {
        KeyMaterial active = keyMaterial("pay-signing-key-1");
        String duplicateConfig = new ObjectMapper().writeValueAsString(List.of(
                Map.of(
                        "kid",
                        active.key().getKeyID(),
                        "verifyUntil",
                        NOW.plusSeconds(60).toString(),
                        "jwk",
                        active.key().toPublicJWK().toJSONObject())));

        IllegalStateException duplicateException = assertThrows(
                IllegalStateException.class,
                () -> new MandateKeySet(
                        properties(active, duplicateConfig, true),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new ObjectMapper()));
        assertTrue(duplicateException.getMessage().contains("unique"));

        MandateProperties missingKey = new MandateProperties(
                "pay-service",
                "pay-signing-key-1",
                "",
                "",
                "",
                "[]",
                300,
                true);
        IllegalStateException missingException = assertThrows(
                IllegalStateException.class,
                () -> new MandateKeySet(
                        missingKey,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new ObjectMapper()));
        assertTrue(missingException.getMessage().contains("required"));
    }

    @Test
    void privateKeyOnlyConfigurationDerivesItsPublicKey() throws Exception {
        KeyMaterial active = keyMaterial("pay-signing-key-1");
        MandateProperties properties = new MandateProperties(
                "pay-service",
                active.key().getKeyID(),
                encodePrivateKey(active.keyPair()),
                "",
                "",
                "[]",
                300,
                true);

        MandateKeySet keySet = new MandateKeySet(
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());

        assertNotNull(keySet.publicKeys().getFirst().getX());
        assertEquals(active.key().getX(), keySet.publicKeys().getFirst().getX());
        assertEquals(active.key().getY(), keySet.publicKeys().getFirst().getY());
    }

    @Test
    void mandatePropertiesBindThroughSpringConstructorBinding() {
        MandateProperties properties = Binder.get(new MockEnvironment()
                .withProperty("pay.mandate.issuer", "pay-service")
                .withProperty("pay.mandate.key-id", "pay-signing-key-1")
                .withProperty("pay.mandate.signing-private-key", "private-key")
                .withProperty("pay.mandate.encryption-key", "encryption-key")
                .withProperty("pay.mandate.public-key-jwk", "public-key")
                .withProperty("pay.mandate.previous-verification-keys-json", "[]")
                .withProperty("pay.mandate.profile-cache-seconds", "300")
                .withProperty("pay.mandate.require-configured-key", "true"))
                .bind("pay.mandate", Bindable.of(MandateProperties.class))
                .orElseThrow(() -> new AssertionError("Mandate properties did not bind"));

        assertEquals("pay-service", properties.issuer());
        assertEquals("pay-signing-key-1", properties.keyId());
        assertEquals("public-key", properties.publicKeyJwk());
        assertEquals(300, properties.profileCacheSeconds());
        assertTrue(properties.requireConfiguredKey());
    }

    private MandateProperties properties(
            KeyMaterial active,
            String previousKeys,
            boolean requireConfiguredKey) {
        return new MandateProperties(
                "pay-service",
                active.key().getKeyID(),
                encodePrivateKey(active.keyPair()),
                "",
                active.key().toPublicJWK().toJSONString(),
                previousKeys,
                300,
                requireConfiguredKey);
    }

    private KeyMaterial keyMaterial(String keyId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair keyPair = generator.generateKeyPair();
        ECKey key = new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .build();
        return new KeyMaterial(keyPair, key);
    }

    private String encodePrivateKey(KeyPair keyPair) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(keyPair.getPrivate().getEncoded());
    }

    private AuthorizationSession session() {
        return AuthorizationSessionFixtures.singleMerchant(
                "session-001",
                "purchase-001",
                "user-001",
                "merchant-account-001",
                "merchant-001",
                "예시 상점",
                "checkout-001",
                "0".repeat(64),
                "{}",
                87000,
                "KRW",
                "method-001",
                AuthorizationSessionStatus.APPROVED,
                "idempotency-001",
                "request-hash",
                "BIOMETRIC",
                NOW.plusSeconds(600),
                NOW,
                "BIOMETRIC",
                NOW,
                NOW,
                1);
    }

    private record KeyMaterial(KeyPair keyPair, ECKey key) {
    }
}
