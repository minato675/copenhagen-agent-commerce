package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.payment.policy.PaymentStatus;

public record CreatedPayment(
        String id,
        String merchantAccountId,
        String externalCheckoutId,
        String purchaseRequestId,
        String credentialTokenId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String idempotencyKey,
        String requestHash) {
}
