package com.copenhagen.pay.payment.credential.mandate;

import java.time.Instant;

public interface MandateCredentialRepository {

    void insert(Issuance issuance);

    record Issuance(
            String checkoutMandateId,
            String authorizationSessionId,
            String sessionItemId,
            String checkoutMandateJti,
            String issuer,
            String audience,
            String checkoutDigest,
            String checkoutArtifactEncrypted,
            String checkoutArtifactHash,
            Instant issuedAt,
            Instant expiresAt,
            String paymentMandateId,
            String paymentMandateJti,
            String transactionId,
            String merchantId,
            String externalCheckoutId,
            long amountMinor,
            String currency,
            String paymentMethodRef,
            String paymentArtifactEncrypted,
            String paymentArtifactHash,
            String credentialId,
            String credentialTokenHash,
            String credentialTokenCiphertext) {
    }
}
