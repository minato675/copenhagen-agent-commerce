package com.copenhagen.pay.payment.payment;

import java.time.Instant;
import java.util.Optional;

public interface PaymentExecutionRepository {

    Optional<PaymentExecutionRecord> findByIdForUpdate(String paymentId);

    Optional<PaymentExecutionRecord> findById(String paymentId);

    void insertChargeAttempt(
            String attemptId,
            String paymentId,
            String requestHash,
            String pgRequestId,
            Instant startedAt);

    void insertPgTransaction(
            String transactionId,
            String attemptId,
            Instant requestedAt);

    boolean updateAttemptResult(
            String attemptId,
            String status,
            String errorCode,
            Instant completedAt);

    boolean updatePgTransactionResult(
            String attemptId,
            String status,
            String pgTransactionId,
            String responseCode,
            Instant completedAt,
            Instant lastCheckedAt);

    void insertReceipt(PaymentReceipt receipt);

    void insertReconciliationJob(
            String attemptId,
            String reason,
            Instant nextRunAt);

    default void insertReconciliationJob(
            String attemptId,
            String reason,
            Instant nextRunAt,
            String requestId) {
        insertReconciliationJob(attemptId, reason, nextRunAt);
    }

    record PaymentReceipt(
            String id,
            String paymentId,
            String receiptHash,
            String artifactEncrypted,
            Instant issuedAt) {
    }
}
