package com.copenhagen.pay.payment.credential.mandate;

import java.time.Instant;

public record IssuedPaymentCredential(
        String credentialId,
        Instant expiresAt,
        String checkoutMandate) {
}
