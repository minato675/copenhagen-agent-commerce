package com.copenhagen.pay.payment.credential.mandate;

import java.time.Instant;
import java.util.Optional;

public interface PaymentCredentialConsumptionRepository {

    Optional<CreatedPayment> findPaymentByMerchantAndIdempotencyKey(
            String merchantAccountId,
            String idempotencyKey);

    Optional<PaymentCredentialRecord> findCredentialForUpdate(String credentialId);

    void insertPayment(CreatedPayment payment);

    boolean consumeCredential(String credentialId, Instant consumedAt);
}
