package com.copenhagen.pay.payment.reconciliation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.observability.PayLogCapture;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.payment.PaymentExecutionRecord;
import com.copenhagen.pay.payment.pg.PaymentGateway;
import com.copenhagen.pay.payment.pg.PaymentGateway.StatusResult;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReconciliationObservabilityTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    @AfterEach
    void clearContext() {
        PayLogContext.clear();
    }

    @Test
    void pgAdapterFailureKeepsJobAttemptAndSafeFailureClassificationInLogs() {
        ReconciliationJobService jobService = mock(ReconciliationJobService.class);
        ReconciliationPaymentRepository paymentRepository = mock(ReconciliationPaymentRepository.class);
        PaymentGateway gateway = mock(PaymentGateway.class);
        ReconciliationSettlementService settlement = mock(ReconciliationSettlementService.class);
        ReconciliationJob job = new ReconciliationJob(
                "job-001",
                "attempt-001",
                "request-reconciliation-001",
                ReconciliationJobStatus.RUNNING,
                3,
                NOW.plusSeconds(60));
        PaymentExecutionRecord payment = payment();
        when(jobService.claimNext()).thenReturn(Optional.of(job));
        when(paymentRepository.findByAttemptId("attempt-001")).thenReturn(Optional.of(payment));
        when(gateway.lookup(any())).thenThrow(new IllegalStateException(
                "PIN=123456 CARD=4111111111111111 Authorization=Bearer synthetic-token "
                        + "Cookie=synthetic-cookie credential=synthetic-credential "
                        + "FCM=synthetic-fcm-token"));

        ReconciliationService service = new ReconciliationService(
                jobService,
                paymentRepository,
                gateway,
                settlement,
                Clock.fixed(NOW, ZoneOffset.UTC));

        try (PayLogCapture logs = new PayLogCapture()) {
            assertTrue(service.processNext());

            assertTrue(logs.allText().contains("jobId=job-001"));
            assertTrue(logs.allText().contains("attemptCount=3"));
            assertTrue(logs.allText().contains("pg_lookup.adapter_failed"));
            assertTrue(logs.allText().contains("exceptionType=IllegalStateException"));
            assertTrue(logs.allText().contains("outcome=UNKNOWN"));
            assertTrue(logs.allText().contains("PG_LOOKUP_UNAVAILABLE"));
            assertTrue(!logs.allText().contains("PIN=123456"));
            assertTrue(logs.events().stream().anyMatch(eventLog ->
                    "request-reconciliation-001".equals(
                            eventLog.getMDCPropertyMap().get(PayLogContext.REQUEST_ID))));
        }

        verify(settlement).settle(job, StatusResult.unknown("PG_LOOKUP_UNAVAILABLE"));
    }

    private PaymentExecutionRecord payment() {
        return new PaymentExecutionRecord(
                "payment-001",
                "approval-001",
                "merchant-account-001",
                "merchant-001",
                "checkout-001",
                "purchase-001",
                "credential-001",
                "CONSUMED",
                "provider-ref",
                87000,
                "KRW",
                PaymentStatus.UNKNOWN,
                "idempotency-001",
                "request-hash",
                "attempt-001",
                "pg-request-001",
                "UNKNOWN",
                "pg-transaction-001",
                "UNKNOWN",
                null,
                null);
    }
}
