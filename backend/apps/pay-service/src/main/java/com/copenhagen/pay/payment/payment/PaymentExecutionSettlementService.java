package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult.Outcome;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PG 결과를 세 개의 결제 원장에 반영하고 성공 시에만 Receipt를 발급합니다. */
@Service
public class PaymentExecutionSettlementService {

    private static final String DEFAULT_RECONCILIATION_REASON = "PG_TIMEOUT";
    private static final long RECONCILIATION_DELAY_SECONDS = 60;
    private static final Logger log = LoggerFactory.getLogger(PaymentExecutionSettlementService.class);

    private final PaymentExecutionRepository repository;
    private final PaymentLifecycleService lifecycleService;
    private final PaymentReceiptService receiptService;
    private final Clock clock;

    public PaymentExecutionSettlementService(
            PaymentExecutionRepository repository,
            PaymentLifecycleService lifecycleService,
            PaymentReceiptService receiptService,
            Clock clock) {
        this.repository = repository;
        this.lifecycleService = lifecycleService;
        this.receiptService = receiptService;
        this.clock = clock;
    }

    @Transactional
    public PaymentExecutionResult settle(
            PreparedPaymentExecution prepared,
            ChargeResult chargeResult) {
        PayLogContext.put(PayLogContext.PAYMENT_ID, prepared.paymentId());
        log.info(
                "pay.payment.settlement.start paymentId={} attemptId={} outcome={}",
                prepared.paymentId(),
                prepared.attemptId(),
                chargeResult == null ? null : chargeResult.outcome());
        PaymentExecutionRecord current = repository.findByIdForUpdate(prepared.paymentId())
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        PayLogContext.put(PayLogContext.ORDER_ID, current.purchaseRequestId());
        if (current.status() != PaymentStatus.PROCESSING) {
            log.info(
                    "pay.payment.settlement.skipped paymentId={} status={}",
                    prepared.paymentId(),
                    current.status());
            return PaymentExecutionResult.from(current);
        }
        if (!prepared.attemptId().equals(current.attemptId())) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    "결제 시도 원장을 찾을 수 없습니다.",
                    current.status().name(),
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction());
        }

        ChargeResult normalized = normalize(chargeResult);
        PaymentStatus targetStatus = toPaymentStatus(normalized.outcome());
        Instant now = clock.instant();
        String errorCode = targetStatus == PaymentStatus.SUCCESS
                ? null
                : responseCodeOrDefault(normalized.responseCode(), targetStatus);

        if (!repository.updateAttemptResult(
                prepared.attemptId(),
                targetStatus.name(),
                errorCode,
                now)) {
            throw persistenceConflict(current.status());
        }
        if (!repository.updatePgTransactionResult(
                prepared.attemptId(),
                targetStatus.name(),
                normalized.pgTransactionId(),
                normalized.responseCode(),
                now,
                now)) {
            throw persistenceConflict(current.status());
        }

        if (targetStatus == PaymentStatus.SUCCESS) {
            lifecycleService.resolveSuccess(current.id());
            receiptService.issueIfAbsent(current, normalized.pgTransactionId(), now);
        } else if (targetStatus == PaymentStatus.FAILED) {
            lifecycleService.resolveDefinitiveFailure(current.id());
        } else {
            lifecycleService.markUnknown(current.id());
            repository.insertReconciliationJob(
                    prepared.attemptId(),
                    reconciliationReason(normalized),
                    now.plusSeconds(RECONCILIATION_DELAY_SECONDS),
                    PayLogContext.get(PayLogContext.REQUEST_ID));
            log.warn(
                    "pay.payment.settlement.reconciliation_scheduled paymentId={} attemptId={}",
                    current.id(),
                    prepared.attemptId());
        }

        PaymentExecutionResult settled = repository.findById(current.id())
                .map(PaymentExecutionResult::from)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        log.info(
                "pay.payment.settlement.complete paymentId={} status={}",
                current.id(),
                settled.status());
        return settled;
    }

    private ChargeResult normalize(ChargeResult result) {
        if (result == null || result.outcome() == null) {
            return ChargeResult.unknown("PG_INVALID_RESPONSE");
        }
        if (result.outcome() == Outcome.SUCCESS
                && (result.pgTransactionId() == null || result.pgTransactionId().isBlank())) {
            return ChargeResult.unknown("PG_INVALID_SUCCESS_RESPONSE");
        }
        return result;
    }

    private PaymentStatus toPaymentStatus(Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> PaymentStatus.SUCCESS;
            case FAILED -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.UNKNOWN;
        };
    }

    private String responseCodeOrDefault(String responseCode, PaymentStatus status) {
        if (responseCode != null && !responseCode.isBlank()) {
            return responseCode;
        }
        return status == PaymentStatus.FAILED ? "PG_DECLINED" : "PG_TIMEOUT";
    }

    private String reconciliationReason(ChargeResult result) {
        if (result.responseCode() == null || result.responseCode().isBlank()) {
            return DEFAULT_RECONCILIATION_REASON;
        }
        return result.responseCode();
    }

    private PayException persistenceConflict(PaymentStatus currentStatus) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }
}
