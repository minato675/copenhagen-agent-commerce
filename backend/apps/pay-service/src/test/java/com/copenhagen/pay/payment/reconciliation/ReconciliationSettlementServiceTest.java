package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.payment.PaymentExecutionRecord;
import com.copenhagen.pay.payment.payment.PaymentLifecycleService;
import com.copenhagen.pay.payment.payment.PaymentReceiptService;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult.Outcome;
import com.copenhagen.pay.payment.pg.PaymentGateway.StatusResult;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconciliationSettlementServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private final ReconciliationJob job = new ReconciliationJob(
            "job-1", "attempt-1", ReconciliationJobStatus.RUNNING, 1, NOW.plusSeconds(60));
    private ReconciliationJobRepository jobs;
    private ReconciliationJobService jobService;
    private ReconciliationPaymentRepository payments;
    private PaymentLifecycleService lifecycle;
    private PaymentReceiptService receipts;
    private ReconciliationSettlementService service;
    private PaymentExecutionRecord payment;

    @BeforeEach
    void setUp() {
        jobs = mock(ReconciliationJobRepository.class);
        jobService = mock(ReconciliationJobService.class);
        payments = mock(ReconciliationPaymentRepository.class);
        lifecycle = mock(PaymentLifecycleService.class);
        receipts = mock(PaymentReceiptService.class);
        service = new ReconciliationSettlementService(jobs, jobService, payments, lifecycle, receipts,
                Clock.fixed(NOW, ZoneOffset.UTC));
        payment = payment(PaymentStatus.UNKNOWN);
        when(jobs.lockOwned(job, NOW)).thenReturn(true);
        when(payments.findByAttemptIdForUpdate("attempt-1")).thenReturn(Optional.of(payment));
        when(payments.updateUnknownTransaction(anyString(), anyString(), nullable(String.class), anyString(), any()))
                .thenReturn(true);
        when(payments.resolveUnknownAttempt(anyString(), anyString(), nullable(String.class), any())).thenReturn(true);
        when(jobService.markSucceeded(job)).thenReturn(true);
        when(jobService.markFailed(job)).thenReturn(true);
        when(jobService.markCancelled(job)).thenReturn(true);
    }

    @Test
    void settle_success_updatesLedgersAndIssuesReceipt() {
        service.settle(job, new StatusResult(Outcome.SUCCESS, "pg-1", "unsafe upstream text"));
        verify(payments).updateUnknownTransaction("attempt-1", "SUCCESS", "pg-1", "PG_APPROVED", NOW);
        verify(payments).resolveUnknownAttempt("attempt-1", "SUCCESS", null, NOW);
        verify(lifecycle).resolveSuccess("payment-1");
        verify(receipts).issueIfAbsent(payment, "pg-1", NOW);
        verify(jobService).markSucceeded(job);
    }

    @Test
    void settle_decline_invalidatesPaymentWithoutReceipt() {
        service.settle(job, new StatusResult(Outcome.FAILED, "pg-1", "DECLINED"));
        verify(payments).updateUnknownTransaction("attempt-1", "FAILED", "pg-1", "PG_DECLINED", NOW);
        verify(payments).resolveUnknownAttempt("attempt-1", "FAILED", "PG_DECLINED", NOW);
        verify(lifecycle).resolveDefinitiveFailure("payment-1");
        verifyNoInteractions(receipts);
        verify(jobService).markSucceeded(job);
    }

    @Test
    void settle_unknown_preservesPaymentAndReschedulesJob() {
        service.settle(job, StatusResult.unknown("timeout with sensitive text"));
        verify(payments).updateUnknownTransaction("attempt-1", "UNKNOWN", null, "PG_LOOKUP_UNCONFIRMED", NOW);
        verify(payments, never()).resolveUnknownAttempt(anyString(), anyString(), nullable(String.class), any());
        verifyNoInteractions(lifecycle, receipts);
        verify(jobService).markFailed(job);
    }

    @Test
    void settle_expiredOwner_ignoresLateResult() {
        when(jobs.lockOwned(job, NOW)).thenReturn(false);
        service.settle(job, new StatusResult(Outcome.SUCCESS, "pg-1", "APPROVED"));
        verifyNoInteractions(payments, lifecycle, receipts, jobService);
    }

    @Test
    void settle_alreadyFinalized_cancelsWithoutChangingPayment() {
        when(payments.findByAttemptIdForUpdate("attempt-1")).thenReturn(Optional.of(payment(PaymentStatus.SUCCESS)));
        service.settle(job, new StatusResult(Outcome.FAILED, "pg-1", "DECLINED"));
        verify(jobService).markCancelled(job);
        verifyNoInteractions(lifecycle, receipts);
    }

    @Test
    void settle_missingTransactionUpdate_throwsForTransactionRollback() {
        when(payments.updateUnknownTransaction(eq("attempt-1"), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);
        assertThrows(PayException.class, () -> service.settle(job, new StatusResult(Outcome.SUCCESS, "pg-1", "OK")));
        verifyNoInteractions(lifecycle, receipts, jobService);
    }

    private PaymentExecutionRecord payment(PaymentStatus status) {
        return new PaymentExecutionRecord("payment-1", "approval-1", "merchant-account-1", "merchant-1",
                "checkout-1", "purchase-1", "credential-1", "CONSUMED", "provider-ref", 87000, "KRW", status,
                "key-1", "hash", "attempt-1", "request-1", "UNKNOWN", null, "UNKNOWN", null, null);
    }
}
