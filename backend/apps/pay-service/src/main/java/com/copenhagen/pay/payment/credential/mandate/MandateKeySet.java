package com.copenhagen.pay.payment.credential.mandate;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
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
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class MandateKeySet {

    private static final String KEY_ALGORITHM = "EC";
    private static final String CURVE_NAME = "secp256r1";
    private static final String PUBLIC_KEY_ERROR =
            "PAY_MANDATE_PUBLIC_KEY_JWK must be a public ES256 P-256 JWK";
    private static final String PRIVATE_KEY_ERROR =
            "PAY_MANDATE_SIGNING_PRIVATE_KEY must be a Base64URL PKCS#8 EC key";

    private final MandateProperties properties;
    private final Clock clock;
    private final ECKey activeKey;
    private final List<VerificationKey> previousKeys;

    @Autowired
    public MandateKeySet(
            MandateProperties properties,
            Clock clock,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.clock = clock;
        validateProperties(properties);
        this.activeKey = resolveActiveKey(properties);
        this.previousKeys = resolvePreviousKeys(properties, objectMapper);
        validateNoDuplicateKeyIds();
    }

    public MandateKeySet(MandateProperties properties) {
        this(properties, Clock.systemUTC(), new ObjectMapper());
    }

    public String issuer() {
        return properties.issuer();
    }

    public long profileCacheSeconds() {
        return properties.profileCacheSeconds();
    }

    public ECPrivateKey signingKey() {
        try {
            return activeKey.toECPrivateKey();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Mandate signing key is unavailable", exception);
        }
    }

    public String signingKeyId() {
        return activeKey.getKeyID();
    }

    public List<ECKey> publicKeys() {
        Instant now = clock.instant();
        List<ECKey> keys = new ArrayList<>();
        keys.add(activeKey.toPublicJWK());
        previousKeys.stream()
                .filter(key -> key.verifyUntil().isAfter(now))
                .map(VerificationKey::key)
                .map(ECKey::toPublicJWK)
                .forEach(keys::add);
        return List.copyOf(keys);
    }

    public Optional<ECKey> findVerificationKey(String keyId) {
        if (activeKey.getKeyID().equals(keyId)) {
            return Optional.of(activeKey.toPublicJWK());
        }
        Instant now = clock.instant();
        return previousKeys.stream()
                .filter(key -> key.key().getKeyID().equals(keyId))
                .filter(key -> key.verifyUntil().isAfter(now))
                .map(VerificationKey::key)
                .map(ECKey::toPublicJWK)
                .findFirst();
    }

    private void validateProperties(MandateProperties value) {
        if (value == null
                || isBlank(value.issuer())
                || isBlank(value.keyId())
                || value.profileCacheSeconds() <= 0) {
            throw new IllegalStateException("Invalid Pay Mandate configuration");
        }
    }

    private ECKey resolveActiveKey(MandateProperties value) {
        String privateKeyValue = value.signingPrivateKey();
        String publicKeyValue = value.publicKeyJwk();
        if (isBlank(privateKeyValue) && isBlank(publicKeyValue)) {
            if (value.requireConfiguredKey()) {
                throw new IllegalStateException(
                        "PAY_MANDATE_SIGNING_PRIVATE_KEY and PAY_MANDATE_PUBLIC_KEY_JWK are required");
            }
            return generateActiveKey(value.keyId());
        }
        if (isBlank(privateKeyValue)) {
            throw new IllegalStateException(
                    "PAY_MANDATE_SIGNING_PRIVATE_KEY is required when a public Mandate key is configured");
        }

        ECPrivateKey privateKey = resolvePrivateKey(privateKeyValue);
        ECPublicKey publicKey = isBlank(publicKeyValue)
                ? derivePublicKey(privateKey)
                : resolvePublicKey(publicKeyValue, value.keyId());
        return buildActiveKey(value.keyId(), privateKey, publicKey);
    }

    private ECKey generateActiveKey(String keyId) {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyPairGenerator.initialize(new ECGenParameterSpec(CURVE_NAME));
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            return buildActiveKey(
                    keyId,
                    (ECPrivateKey) keyPair.getPrivate(),
                    (ECPublicKey) keyPair.getPublic());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate mandate signing key", exception);
        }
    }

    private ECKey buildActiveKey(
            String keyId,
            ECPrivateKey privateKey,
            ECPublicKey publicKey) {
        try {
            ECKey key = new ECKey.Builder(Curve.P_256, publicKey)
                    .keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .privateKey(privateKey)
                    .build();
            validateKeyPair(key);
            return key;
        } catch (JOSEException | RuntimeException exception) {
            throw new IllegalStateException("Configured Mandate signing key pair is invalid", exception);
        }
    }

    private ECPrivateKey resolvePrivateKey(String configuredKey) {
        try {
            byte[] encodedKey = Base64.getUrlDecoder().decode(
                    configuredKey.getBytes(StandardCharsets.US_ASCII));
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            if (!(keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encodedKey))
                    instanceof ECPrivateKey privateKey)) {
                throw new IllegalArgumentException(PRIVATE_KEY_ERROR);
            }
            return privateKey;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(PRIVATE_KEY_ERROR, exception);
        }
    }

    private ECPublicKey resolvePublicKey(String configuredKey, String expectedKeyId) {
        try {
            ECKey key = ECKey.parse(configuredKey);
            validatePublicJwk(key, expectedKeyId);
            return key.toECPublicKey();
        } catch (java.text.ParseException | JOSEException | RuntimeException exception) {
            throw new IllegalStateException(PUBLIC_KEY_ERROR, exception);
        }
    }

    private List<VerificationKey> resolvePreviousKeys(
            MandateProperties value,
            ObjectMapper objectMapper) {
        String configuredKeys = value.previousVerificationKeysJson();
        if (isBlank(configuredKeys)) {
            return List.of();
        }
        try {
            Object parsed = objectMapper.readValue(configuredKeys, List.class);
            if (!(parsed instanceof List<?> entries)) {
                throw new IllegalArgumentException(
                        "PAY_MANDATE_PREVIOUS_VERIFICATION_KEYS_JSON must be a JSON array");
            }
            List<VerificationKey> keys = new ArrayList<>();
            for (Object entry : entries) {
                keys.add(resolvePreviousKey(entry));
            }
            return List.copyOf(keys);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "PAY_MANDATE_PREVIOUS_VERIFICATION_KEYS_JSON is invalid", exception);
        }
    }

    private VerificationKey resolvePreviousKey(Object value) {
        Map<String, Object> entry = asStringMap(value, "previous verification key");
        String keyId = requiredString(entry, "kid", "previous verification key kid");
        String verifyUntilValue = requiredString(
                entry,
                "verifyUntil",
                "previous verification key verifyUntil");
        Instant verifyUntil;
        try {
            verifyUntil = Instant.parse(verifyUntilValue);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "previous verification key verifyUntil must be an ISO-8601 instant",
                    exception);
        }
        ECKey key;
        try {
            key = ECKey.parse(asStringMap(entry.get("jwk"), "previous verification key jwk"));
            validatePublicJwk(key, keyId);
        } catch (java.text.ParseException | RuntimeException exception) {
            throw new IllegalArgumentException("previous verification key jwk is invalid", exception);
        }
        return new VerificationKey(key.toPublicJWK(), verifyUntil);
    }

    private void validatePublicJwk(ECKey key, String expectedKeyId) {
        if (key.isPrivate()
                || !expectedKeyId.equals(key.getKeyID())
                || !Curve.P_256.equals(key.getCurve())
                || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                || !JWSAlgorithm.ES256.equals(key.getAlgorithm())
                || key.getX() == null
                || key.getY() == null) {
            throw new IllegalArgumentException(PUBLIC_KEY_ERROR);
        }
    }

    private void validateKeyPair(ECKey key) throws JOSEException {
        JWSObject probe = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(key.getKeyID())
                        .build(),
                new Payload("pay-mandate-key-pair-check"));
        probe.sign(new ECDSASigner(key.toECPrivateKey()));
        JWSVerifier verifier = new ECDSAVerifier(key.toECPublicKey());
        if (!probe.verify(verifier)) {
            throw new IllegalArgumentException("Mandate signing key pair does not match");
        }
    }

    private void validateNoDuplicateKeyIds() {
        Set<String> keyIds = new HashSet<>();
        keyIds.add(activeKey.getKeyID());
        for (VerificationKey key : previousKeys) {
            if (!keyIds.add(key.key().getKeyID())) {
                throw new IllegalStateException("Mandate verification key kid must be unique");
            }
        }
    }

    private ECPublicKey derivePublicKey(ECPrivateKey privateKey) {
        try {
            ECNamedCurveParameterSpec curve = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
            org.bouncycastle.math.ec.ECPoint point = curve.getG()
                    .multiply(privateKey.getS())
                    .normalize();
            java.security.spec.ECPoint publicPoint = new java.security.spec.ECPoint(
                    point.getAffineXCoord().toBigInteger(),
                    point.getAffineYCoord().toBigInteger());
            return (ECPublicKey) KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(
                    new ECPublicKeySpec(publicPoint, privateKey.getParams()));
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalStateException(PUBLIC_KEY_ERROR, exception);
        }
    }

    private Map<String, Object> asStringMap(Object value, String description) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(description + " must be a JSON object");
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(description + " has a non-string field");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private String requiredString(Map<String, Object> value, String key, String description) {
        Object field = value.get(key);
        if (!(field instanceof String string) || isBlank(string)) {
            throw new IllegalArgumentException(description + " is required");
        }
        return string;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record VerificationKey(ECKey key, Instant verifyUntil) {
    }
}
