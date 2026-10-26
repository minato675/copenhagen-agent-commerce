package com.copenhagen.pay.payment.reconciliation;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.payment.PaymentExecutionRecord;
import com.copenhagen.pay.payment.payment.PaymentLifecycleService;
import com.copenhagen.pay.payment.payment.PaymentReceiptService;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult.Outcome;
import com.copenhagen.pay.payment.pg.PaymentGateway.StatusResult;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationSettlementService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationSettlementService.class);

    private final ReconciliationJobRepository jobRepository;
    private final ReconciliationJobService jobService;
    private final ReconciliationPaymentRepository paymentRepository;
    private final PaymentLifecycleService lifecycleService;
    private final PaymentReceiptService receiptService;
    private final Clock clock;

    public ReconciliationSettlementService(
            ReconciliationJobRepository jobRepository,
            ReconciliationJobService jobService,
            ReconciliationPaymentRepository paymentRepository,
            PaymentLifecycleService lifecycleService,
            PaymentReceiptService receiptService,
            Clock clock) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.paymentRepository = paymentRepository;
        this.lifecycleService = lifecycleService;
        this.receiptService = receiptService;
        this.clock = clock;
    }

    @Transactional
    public void settle(ReconciliationJob job, StatusResult result) {
        log.info(
                "pay.reconciliation.settlement.start jobId={} attemptId={} outcome={}",
                job.id(),
                job.paymentAttemptId(),
                result == null ? null : result.outcome());
        Instant now = clock.instant();
        if (!jobRepository.lockOwned(job, now)) {
            log.warn("pay.reconciliation.settlement.skipped jobId={} reason=LEASE_NOT_OWNED", job.id());
            return;
        }
        PaymentExecutionRecord payment = paymentRepository.findByAttemptIdForUpdate(job.paymentAttemptId())
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        PayLogContext.put(PayLogContext.PAYMENT_ID, payment.id());
        if (payment.status() != PaymentStatus.UNKNOWN) {
            requireUpdated(jobService.markCancelled(job));
            log.info("pay.reconciliation.settlement.cancelled jobId={} reason=PAYMENT_NOT_UNKNOWN", job.id());
            return;
        }
        if (!job.paymentAttemptId().equals(payment.attemptId())
                || !"UNKNOWN".equals(payment.attemptStatus())
                || !"UNKNOWN".equals(payment.pgTransactionStatus())) {
            throw new PayException(PayErrorCode.STATE_CONFLICT);
        }

        StatusResult normalized = normalize(result, payment);
        Outcome outcome = normalized.outcome();
        String code = switch (outcome) {
            case SUCCESS -> "PG_APPROVED";
            case FAILED -> "PG_DECLINED";
            case UNKNOWN -> "PG_LOOKUP_UNCONFIRMED";
        };
        requireUpdated(paymentRepository.updateUnknownTransaction(
                payment.attemptId(), outcome.name(), normalized.pgTransactionId(), code, now));
        if (outcome == Outcome.UNKNOWN) {
            requireUpdated(jobService.markFailed(job));
            log.warn("pay.reconciliation.settlement.retry jobId={} outcome=UNKNOWN", job.id());
            return;
        }
        requireUpdated(paymentRepository.resolveUnknownAttempt(
                payment.attemptId(), outcome.name(), outcome == Outcome.SUCCESS ? null : code, now));
        if (outcome == Outcome.SUCCESS) {
            lifecycleService.resolveSuccess(payment.id());
            receiptService.issueIfAbsent(payment, normalized.pgTransactionId(), now);
        } else {
            lifecycleService.resolveDefinitiveFailure(payment.id());
        }
        requireUpdated(jobService.markSucceeded(job));
        log.info(
                "pay.reconciliation.settlement.complete jobId={} paymentId={} status={}",
                job.id(),
                payment.id(),
                outcome);
    }

    private StatusResult normalize(StatusResult result, PaymentExecutionRecord payment) {
        if (result == null || result.outcome() == null) {
            return StatusResult.unknown("PG_INVALID_RESPONSE");
        }
        if (result.outcome() == Outcome.SUCCESS
                && (result.pgTransactionId() == null || result.pgTransactionId().isBlank())) {
            return StatusResult.unknown("PG_INVALID_SUCCESS_RESPONSE");
        }
        if (payment.pgTransactionId() != null && result.pgTransactionId() != null
                && !payment.pgTransactionId().equals(result.pgTransactionId())) {
            return StatusResult.unknown("PG_LOOKUP_TRANSACTION_MISMATCH");
        }
        return result;
    }

    private void requireUpdated(boolean updated) {
        if (!updated) {
            throw new PayException(PayErrorCode.STATE_CONFLICT);
        }
    }
}
