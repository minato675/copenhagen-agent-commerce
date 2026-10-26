package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.payment.payment.PaymentExecutionRecord;
import java.time.Instant;
import java.util.Optional;

/** Existing ledgers only: reconciliation has no payment or attempt creation methods. */
public interface ReconciliationPaymentRepository {

    Optional<PaymentExecutionRecord> findByAttemptId(String attemptId);

    Optional<PaymentExecutionRecord> findByAttemptIdForUpdate(String attemptId);

    boolean resolveUnknownAttempt(String attemptId, String status, String errorCode, Instant now);

    boolean updateUnknownTransaction(
            String attemptId, String status, String pgTransactionId, String responseCode, Instant now);
}
