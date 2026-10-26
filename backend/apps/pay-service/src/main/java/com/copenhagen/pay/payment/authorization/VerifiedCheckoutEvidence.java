package com.copenhagen.pay.payment.authorization;

import java.time.Instant;

public record VerifiedCheckoutEvidence(
        String id,
        String authorizationSessionId,
        String sessionItemId,
        String merchantId,
        String externalCheckoutId,
        String checkoutDigest,
        String canonicalCheckoutJson,
        String merchantAuthorization,
        String merchantKeyId,
        String signatureAlgorithm,
        boolean signatureVerified,
        long amountMinor,
        String currency,
        Instant verifiedAt,
        Instant expiresAt) {

    public static VerifiedCheckoutEvidence from(
            String id,
            String authorizationSessionId,
            String sessionItemId,
            CreateAuthorizationSessionCommand.Item item,
            String checkoutDigest,
            MerchantCheckoutVerifier.VerificationResult verification,
            Instant verifiedAt) {
        return new VerifiedCheckoutEvidence(
                id,
                authorizationSessionId,
                sessionItemId,
                item.merchantId(),
                item.checkoutId(),
                checkoutDigest,
                verification.canonicalCheckoutJson(),
                item.checkoutJwt(),
                verification.merchantKeyId(),
                verification.signatureAlgorithm(),
                verification.signatureVerified(),
                verification.amountMinor(),
                verification.currency(),
                verifiedAt,
                verification.expiresAt());
    }
}
