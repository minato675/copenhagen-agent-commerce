package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.payment.PaymentExecutionRecord;
import com.copenhagen.pay.payment.pg.PaymentGateway;
import com.copenhagen.pay.payment.pg.PaymentGateway.StatusRequest;
import com.copenhagen.pay.payment.pg.PaymentGateway.StatusResult;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import com.copenhagen.pay.payment.policy.ReconciliationJobStatus;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Coordinates short database transactions around a read-only PG network call. */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationJobService jobs;
    private final ReconciliationPaymentRepository payments;
    private final PaymentGateway gateway;
    private final ReconciliationSettlementService settlement;
    private final Clock clock;

    public ReconciliationService(
            ReconciliationJobService jobs,
            ReconciliationPaymentRepository payments,
            PaymentGateway gateway,
            ReconciliationSettlementService settlement,
            Clock clock) {
        this.jobs = jobs;
        this.payments = payments;
        this.gateway = gateway;
        this.settlement = settlement;
        this.clock = clock;
    }

    public boolean processNext() {
        Optional<ReconciliationJob> claimed = jobs.claimNext();
        if (claimed.isEmpty()) {
            log.debug("pay.reconciliation.poll.empty");
            return false;
        }
        ReconciliationJob job = claimed.orElseThrow();
        String requestId = job.requestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = PayLogContext.get(PayLogContext.REQUEST_ID);
            if (requestId == null || requestId.isBlank()) {
                requestId = "reconciliation-" + UUID.randomUUID();
            }
        }
        try (PayLogContext.Scope ignored = PayLogContext.open(
                PayLogContext.REQUEST_ID, requestId)) {
            return processClaimed(job);
        }
    }

    private boolean processClaimed(ReconciliationJob job) {
        log.info(
                "pay.reconciliation.start jobId={} attemptId={} attemptCount={}",
                job.id(),
                job.paymentAttemptId(),
                job.attemptCount());
        if (job.status() != ReconciliationJobStatus.RUNNING
                || job.leaseExpiresAt() == null || !clock.instant().isBefore(job.leaseExpiresAt())) {
            log.warn("pay.reconciliation.skipped jobId={} reason=LEASE_NOT_ACTIVE", job.id());
            return true;
        }
        Optional<PaymentExecutionRecord> found = payments.findByAttemptId(job.paymentAttemptId());
        if (found.isEmpty()) {
            log.warn("pay.reconciliation.failed jobId={} reason=PAYMENT_NOT_FOUND", job.id());
            jobs.markFailed(job);
            return true;
        }
        PaymentExecutionRecord payment = found.orElseThrow();
        PayLogContext.put(PayLogContext.PAYMENT_ID, payment.id());
        PayLogContext.put(PayLogContext.ORDER_ID, payment.purchaseRequestId());
        log.info(
                "pay.reconciliation.payment_loaded jobId={} paymentId={} status={}",
                job.id(),
                payment.id(),
                payment.status());
        if (payment.status() != PaymentStatus.UNKNOWN) {
            log.info("pay.reconciliation.cancelled jobId={} reason=PAYMENT_NOT_UNKNOWN", job.id());
            jobs.markCancelled(job);
            return true;
        }
        if (!"UNKNOWN".equals(payment.attemptStatus()) || !"UNKNOWN".equals(payment.pgTransactionStatus())) {
            log.warn("pay.reconciliation.failed jobId={} reason=STATE_MISMATCH", job.id());
            jobs.markFailed(job);
            return true;
        }
        StatusResult result;
        try {
            log.info("pay.reconciliation.pg_lookup.start jobId={} paymentId={}", job.id(), payment.id());
            result = gateway.lookup(new StatusRequest(payment.pgTransactionId(), payment.pgRequestId()));
        } catch (RuntimeException exception) {
            // The external adapter boundary must not leak exception messages or trigger a charge retry.
            log.error(
                    "pay.reconciliation.pg_lookup.adapter_failed jobId={} paymentId={} exceptionType={}",
                    job.id(),
                    payment.id(),
                    exception.getClass().getSimpleName());
            result = StatusResult.unknown("PG_LOOKUP_UNAVAILABLE");
        }
        log.info(
                "pay.reconciliation.pg_lookup.complete jobId={} paymentId={} outcome={} "
                        + "pgErrorCode={}",
                job.id(),
                payment.id(),
                result.outcome(),
                result.responseCode());
        settlement.settle(job, result);
        log.info("pay.reconciliation.complete jobId={} paymentId={}", job.id(), payment.id());
        return true;
    }
}
