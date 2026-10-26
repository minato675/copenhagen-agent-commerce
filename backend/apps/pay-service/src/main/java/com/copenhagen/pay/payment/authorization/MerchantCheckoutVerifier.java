package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.merchant.MerchantKeyLookupException;
import com.copenhagen.pay.payment.merchant.MerchantPublicKeyClient;
import com.copenhagen.pay.payment.merchant.MerchantServiceProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.math.BigDecimal;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MerchantCheckoutVerifier {

    private static final String CLAIM_MERCHANT_ID = "merchantId";
    private static final String CLAIM_CHECKOUT_ID = "checkoutId";
    private static final String CLAIM_PURCHASE_REQUEST_ID = "purchaseRequestId";
    private static final String CLAIM_AMOUNT_MINOR = "amountMinor";
    private static final String CLAIM_CURRENCY = "currency";
    private static final String CLAIM_CHECKOUT_HASH = "checkoutHash";

    private final MerchantPublicKeyClient publicKeyClient;
    private final MerchantServiceProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MerchantCheckoutVerifier(
            MerchantPublicKeyClient publicKeyClient,
            MerchantServiceProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.publicKeyClient = publicKeyClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public VerificationResult verify(
            String purchaseRequestId,
            CreateAuthorizationSessionCommand.Item command) {
        SignedJWT signedJwt = parse(command);
        JWSHeader header = signedJwt.getHeader();
        String algorithm = header.getAlgorithm() == null
                ? ""
                : header.getAlgorithm().getName();
        if (!properties.allowedAlgorithmNames().contains(algorithm)) {
            throw invalidSignature();
        }
        String keyId = header.getKeyID();
        if (keyId == null || keyId.isBlank()) {
            throw invalidSignature();
        }

        Map<String, Object> keyMaterial = findKey(command.merchantId(), keyId);
        if (!isActiveSigningKey(keyMaterial, algorithm)) {
            throw invalidSignature();
        }
        JWK verificationKey = parsePublicKey(keyMaterial);
        if (!matchesKey(verificationKey, keyId, algorithm)) {
            throw invalidSignature();
        }

        verifySignature(signedJwt, algorithm, verificationKey);
        JWTClaimsSet claims = claims(signedJwt);
        validateClaims(purchaseRequestId, command, claims);
        return new VerificationResult(
                keyId,
                algorithm,
                serializeClaims(claims),
                requiredLongClaim(claims, CLAIM_AMOUNT_MINOR),
                requiredStringClaim(claims, CLAIM_CURRENCY),
                claims.getExpirationTime().toInstant(),
                true);
    }

    private SignedJWT parse(CreateAuthorizationSessionCommand.Item command) {
        if (command == null || command.checkoutJwt() == null || command.checkoutJwt().isBlank()) {
            throw invalidRequest("checkoutJwt가 없습니다.", "CHECK_CHECKOUT_JWT");
        }
        try {
            return SignedJWT.parse(command.checkoutJwt());
        } catch (ParseException exception) {
            throw invalidRequest(
                    "checkoutJwt는 유효한 Compact JWS여야 합니다.",
                    "CHECK_CHECKOUT_JWT");
        }
    }

    private Map<String, Object> findKey(String merchantId, String keyId) {
        List<Map<String, Object>> keys;
        try {
            keys = publicKeyClient.findPublicKeys(merchantId);
        } catch (MerchantKeyLookupException exception) {
            throw keyUnavailable(exception);
        } catch (RuntimeException exception) {
            throw keyUnavailable(exception);
        }
        if (keys == null || keys.isEmpty()) {
            throw keyUnavailable(null);
        }

        List<Map<String, Object>> matchingKeys = keys.stream()
                .filter(Objects::nonNull)
                .filter(key -> keyId.equals(key.get("kid")))
                .toList();
        if (matchingKeys.size() > 1) {
            throw keyUnavailable(null);
        }
        if (matchingKeys.isEmpty()) {
            throw invalidSignature();
        }
        return matchingKeys.getFirst();
    }

    private JWK parsePublicKey(Map<String, Object> keyMaterial) {
        if (keyMaterial.containsKey("d")) {
            throw keyUnavailable(null);
        }
        try {
            return JWK.parse(keyMaterial);
        } catch (ParseException exception) {
            throw keyUnavailable(exception);
        }
    }

    private boolean isActiveSigningKey(Map<String, Object> keyMaterial, String algorithm) {
        Object active = keyMaterial.get("active");
        if (active instanceof Boolean && !((Boolean) active)) {
            return false;
        }
        Object status = keyMaterial.get("status");
        if (status != null && !"ACTIVE".equalsIgnoreCase(String.valueOf(status))) {
            return false;
        }
        Object use = keyMaterial.get("use");
        if (use != null && !"sig".equals(use)) {
            return false;
        }
        Object keyOperations = keyMaterial.get("key_ops");
        if (keyOperations instanceof Collection<?> operations
                && !operations.isEmpty()
                && operations.stream().noneMatch("verify"::equals)) {
            return false;
        }
        Object keyAlgorithm = keyMaterial.get("alg");
        return keyAlgorithm == null || algorithm.equals(String.valueOf(keyAlgorithm));
    }

    private boolean matchesKey(JWK key, String keyId, String algorithm) {
        if (!keyId.equals(key.getKeyID())) {
            return false;
        }
        if (key.getAlgorithm() != null && !algorithm.equals(key.getAlgorithm().getName())) {
            return false;
        }
        return switch (algorithm) {
            case "ES256" -> key instanceof ECKey ecKey && Curve.P_256.equals(ecKey.getCurve());
            case "ES384" -> key instanceof ECKey ecKey && Curve.P_384.equals(ecKey.getCurve());
            case "ES512" -> key instanceof ECKey ecKey && Curve.P_521.equals(ecKey.getCurve());
            case "RS256", "RS384", "RS512" -> key instanceof RSAKey;
            default -> false;
        };
    }

    private void verifySignature(SignedJWT signedJwt, String algorithm, JWK key) {
        try {
            JWSVerifier verifier = switch (algorithm) {
                case "ES256", "ES384", "ES512" ->
                        new ECDSAVerifier(((ECKey) key).toECPublicKey());
                case "RS256", "RS384", "RS512" ->
                        new RSASSAVerifier(((RSAKey) key).toRSAPublicKey());
                default -> throw invalidSignature();
            };
            if (!signedJwt.verify(verifier)) {
                throw invalidSignature();
            }
        } catch (JOSEException | ClassCastException exception) {
            throw invalidSignature();
        }
    }

    private JWTClaimsSet claims(SignedJWT signedJwt) {
        try {
            return signedJwt.getJWTClaimsSet();
        } catch (ParseException exception) {
            throw invalidRequest(
                    "checkoutJwt Claim 형식이 올바르지 않습니다.",
                    "CHECK_CHECKOUT_JWT");
        }
    }

    private void validateClaims(
            String purchaseRequestId,
            CreateAuthorizationSessionCommand.Item command,
            JWTClaimsSet claims) {
        compareStringClaim(command.merchantId(), claims, CLAIM_MERCHANT_ID);
        compareStringClaim(command.checkoutId(), claims, CLAIM_CHECKOUT_ID);
        compareStringClaim(purchaseRequestId, claims, CLAIM_PURCHASE_REQUEST_ID);
        long claimAmountMinor = requiredLongClaim(claims, CLAIM_AMOUNT_MINOR);
        if (claimAmountMinor != command.amountMinor()) {
            throw invalidRequest("checkoutJwt의 amountMinor가 요청과 다릅니다.", "CHECKOUT_CLAIMS");
        }
        compareStringClaim(command.currency(), claims, CLAIM_CURRENCY);
        Object checkoutHash = claims.getClaim(CLAIM_CHECKOUT_HASH);
        if (checkoutHash != null && !command.checkoutHash().equals(checkoutHash)) {
            throw invalidRequest("checkoutJwt의 checkoutHash가 요청과 다릅니다.", "CHECKOUT_CLAIMS");
        }
        Date expirationTime = claims.getExpirationTime();
        if (expirationTime == null || !expirationTime.toInstant().isAfter(clock.instant())) {
            throw invalidRequest("checkoutJwt가 만료되었거나 exp Claim이 없습니다.", "RESTART_CHECKOUT");
        }
    }

    private void compareStringClaim(
            String expected,
            JWTClaimsSet claims,
            String claimName) {
        if (!expected.equals(requiredStringClaim(claims, claimName))) {
            throw invalidRequest(
                    "checkoutJwt의 " + claimName + "이 요청과 다릅니다.",
                    "CHECKOUT_CLAIMS");
        }
    }

    private String requiredStringClaim(JWTClaimsSet claims, String claimName) {
        Object value = claims.getClaim(claimName);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw invalidRequest(
                    "checkoutJwt에 필수 Claim이 없습니다.",
                    "CHECKOUT_CLAIMS");
        }
        return stringValue;
    }

    private long requiredLongClaim(JWTClaimsSet claims, String claimName) {
        Object value = claims.getClaim(claimName);
        if (!(value instanceof Number number)) {
            throw invalidRequest(
                    "checkoutJwt의 amountMinor Claim 형식이 올바르지 않습니다.",
                    "CHECKOUT_CLAIMS");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalidRequest(
                    "checkoutJwt의 amountMinor Claim 형식이 올바르지 않습니다.",
                    "CHECKOUT_CLAIMS");
        }
    }

    private String serializeClaims(JWTClaimsSet claims) {
        try {
            return objectMapper.writeValueAsString(claims.getClaims());
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    PayErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    private PayException invalidSignature() {
        return new PayException(
                PayErrorCode.CHECKOUT_SIGNATURE_INVALID,
                PayErrorCode.CHECKOUT_SIGNATURE_INVALID.defaultMessage(),
                null,
                PayErrorCode.CHECKOUT_SIGNATURE_INVALID.defaultNextAction());
    }

    private PayException keyUnavailable(Throwable cause) {
        return new PayException(
                PayErrorCode.MERCHANT_KEY_UNAVAILABLE,
                PayErrorCode.MERCHANT_KEY_UNAVAILABLE.defaultMessage(),
                null,
                PayErrorCode.MERCHANT_KEY_UNAVAILABLE.defaultNextAction(),
                cause);
    }

    private PayException invalidRequest(String message, String nextAction) {
        return new PayException(PayErrorCode.INVALID_REQUEST, message, null, nextAction);
    }

    public record VerificationResult(
            String merchantKeyId,
            String signatureAlgorithm,
            String canonicalCheckoutJson,
            long amountMinor,
            String currency,
            Instant expiresAt,
            boolean signatureVerified) {
    }
}
