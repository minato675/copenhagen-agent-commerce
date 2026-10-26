package com.copenhagen.pay.payment.credential.mandate;

import java.time.Instant;

public record PaymentCredentialRecord(
        String id,
        String authorizationSessionId,
        String merchantAccountId,
        String merchantId,
        String purchaseRequestId,
        String externalCheckoutId,
        String checkoutDigest,
        long amountMinor,
        String currency,
        PaymentCredentialStatus status,
        Instant expiresAt,
        Instant consumedAt,
        MandateStatus checkoutMandateStatus,
        MandateStatus paymentMandateStatus) {

    public PaymentCredentialRecord(
            String id,
            String authorizationSessionId,
            String merchantAccountId,
            String merchantId,
            String purchaseRequestId,
            String externalCheckoutId,
            String checkoutDigest,
            long amountMinor,
            String currency,
            PaymentCredentialStatus status,
            Instant expiresAt,
            Instant consumedAt) {
        this(
                id,
                authorizationSessionId,
                merchantAccountId,
                merchantId,
                purchaseRequestId,
                externalCheckoutId,
                checkoutDigest,
                amountMinor,
                currency,
                status,
                expiresAt,
                consumedAt,
                MandateStatus.ISSUED,
                MandateStatus.ISSUED);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
