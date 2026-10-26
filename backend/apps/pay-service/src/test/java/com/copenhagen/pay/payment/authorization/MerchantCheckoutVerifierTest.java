package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.merchant.MerchantKeyLookupException;
import com.copenhagen.pay.payment.merchant.MerchantServiceProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MerchantCheckoutVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String KEY_ID = "merchant-a-es256-1";
    private ECKey signingKey;
    private MerchantCheckoutVerifier verifier;

    @BeforeEach
    void setUp() throws JOSEException {
        signingKey = new ECKeyGenerator(Curve.P_256)
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.ES256)
                .generate();
        verifier = new MerchantCheckoutVerifier(
                merchantId -> List.of(signingKey.toPublicJWK().toJSONObject()),
                properties("ES256"),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void verifiesSignatureAndRequiredClaims() {
        String checkoutJwt = signedCheckout(signingKey, KEY_ID, "merchant-001", "purchase-001", NOW.plusSeconds(300));

        MerchantCheckoutVerifier.VerificationResult result = verifier.verify(PURCHASE_REQUEST_ID, command(checkoutJwt));

        assertEquals(KEY_ID, result.merchantKeyId());
        assertEquals("ES256", result.signatureAlgorithm());
        assertEquals(99000, result.amountMinor());
        assertEquals("KRW", result.currency());
        assertEquals(NOW.plusSeconds(300), result.expiresAt());
        org.junit.jupiter.api.Assertions.assertTrue(result.signatureVerified());
    }

    @Test
    void rejectsSignatureSignedByAnotherKey() throws JOSEException {
        ECKey otherKey = new ECKeyGenerator(Curve.P_256)
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.ES256)
                .generate();
        String checkoutJwt = signedCheckout(otherKey, KEY_ID, "merchant-001", "purchase-001", NOW.plusSeconds(300));

        PayException exception = assertThrows(
                PayException.class,
                () -> verifier.verify(PURCHASE_REQUEST_ID, command(checkoutJwt)));

        assertEquals(PayErrorCode.CHECKOUT_SIGNATURE_INVALID, exception.errorCode());
    }

    @Test
    void rejectsUnsupportedAlgorithm() throws JOSEException {
        ECKey otherKey = new ECKeyGenerator(Curve.P_256)
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.ES256)
                .generate();
        String checkoutJwt = signedCheckout(otherKey, KEY_ID, "merchant-001", "purchase-001", NOW.plusSeconds(300));

        PayException exception = assertThrows(
                PayException.class,
                () -> new MerchantCheckoutVerifier(
                        merchantId -> List.of(signingKey.toPublicJWK().toJSONObject()),
                        properties("RS256"),
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                        .verify(PURCHASE_REQUEST_ID, command(checkoutJwt)));

        assertEquals(PayErrorCode.CHECKOUT_SIGNATURE_INVALID, exception.errorCode());
    }

    @Test
    void rejectsInactiveKey() {
        Map<String, Object> inactiveKey = new java.util.LinkedHashMap<>(signingKey.toPublicJWK().toJSONObject());
        inactiveKey.put("active", false);
        MerchantCheckoutVerifier inactiveKeyVerifier = new MerchantCheckoutVerifier(
                merchantId -> List.of(inactiveKey),
                properties("ES256"),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        String checkoutJwt = signedCheckout(signingKey, KEY_ID, "merchant-001", "purchase-001", NOW.plusSeconds(300));

        PayException exception = assertThrows(
                PayException.class,
                () -> inactiveKeyVerifier.verify(PURCHASE_REQUEST_ID, command(checkoutJwt)));

        assertEquals(PayErrorCode.CHECKOUT_SIGNATURE_INVALID, exception.errorCode());
    }

    @Test
    void rejectsClaimMismatchAsInvalidRequest() {
        String checkoutJwt = signedCheckout(
                signingKey,
                KEY_ID,
                "merchant-001",
                "different-purchase",
                NOW.plusSeconds(300));

        PayException exception = assertThrows(
                PayException.class,
                () -> verifier.verify(PURCHASE_REQUEST_ID, command(checkoutJwt)));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
    }

    @Test
    void rejectsExpiredCheckoutAsInvalidRequest() {
        String checkoutJwt = signedCheckout(signingKey, KEY_ID, "merchant-001", "purchase-001", NOW);

        PayException exception = assertThrows(
                PayException.class,
                () -> verifier.verify(PURCHASE_REQUEST_ID, command(checkoutJwt)));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
    }

    @Test
    void mapsKeyLookupFailureToFiveHundredError() {
        MerchantCheckoutVerifier unavailableVerifier = new MerchantCheckoutVerifier(
                merchantId -> {
                    throw new MerchantKeyLookupException("profile unavailable");
                },
                properties("ES256"),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        String checkoutJwt = signedCheckout(signingKey, KEY_ID, "merchant-001", "purchase-001", NOW.plusSeconds(300));

        PayException exception = assertThrows(
                PayException.class,
                () -> unavailableVerifier.verify(PURCHASE_REQUEST_ID, command(checkoutJwt)));

        assertEquals(PayErrorCode.MERCHANT_KEY_UNAVAILABLE, exception.errorCode());
    }

    private MerchantServiceProperties properties(String allowedAlgorithms) {
        return new MerchantServiceProperties(
                "http://merchant.test",
                "/.well-known/ucp",
                1000,
                1000,
                allowedAlgorithms);
    }

    private static final String PURCHASE_REQUEST_ID = "purchase-001";

    private CreateAuthorizationSessionCommand.Item command(String checkoutJwt) {
        return new CreateAuthorizationSessionCommand.Item(
                "merchant-001",
                "checkout-001",
                "sha256:checkout-hash",
                checkoutJwt,
                null,
                99000,
                "KRW");
    }

    private String signedCheckout(
            ECKey key,
            String keyId,
            String merchantId,
            String purchaseRequestId,
            Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("merchantId", merchantId)
                .claim("checkoutId", "checkout-001")
                .claim("purchaseRequestId", purchaseRequestId)
                .claim("amountMinor", 99000)
                .claim("currency", "KRW")
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(),
                claims);
        try {
            signedJwt.sign(new ECDSASigner(key.toECPrivateKey()));
            return signedJwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("test checkout signing failed", exception);
        }
    }
}
