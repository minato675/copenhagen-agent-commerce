package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.common.validation.RequestValidation;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeRequest;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 결제 실행 권한을 한 번만 예약하고 PG 요청에 필요한 원장을 먼저 기록합니다. */
@Service
public class PaymentExecutionPreparationService {

    private static final String CONSUMED = "CONSUMED";
    private static final Logger log = LoggerFactory.getLogger(PaymentExecutionPreparationService.class);

    private final PaymentExecutionRepository repository;
    private final PaymentLifecycleService lifecycleService;
    private final IdempotencyGuard idempotencyGuard;
    private final Clock clock;

    public PaymentExecutionPreparationService(
            PaymentExecutionRepository repository,
            PaymentLifecycleService lifecycleService,
            IdempotencyGuard idempotencyGuard,
            Clock clock) {
        this.repository = repository;
        this.lifecycleService = lifecycleService;
        this.idempotencyGuard = idempotencyGuard;
        this.clock = clock;
    }

    @Transactional
    public PreparedPaymentExecution prepare(PaymentExecutionCommand command) {
        validate(command);
        PaymentExecutionRecord payment = repository.findByIdForUpdate(command.paymentId())
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
        PayLogContext.put(PayLogContext.PAYMENT_ID, payment.id());
        PayLogContext.put(PayLogContext.ORDER_ID, payment.purchaseRequestId());
        log.info(
                "pay.payment.execution.prepare.start status={}",
                payment.status());

        ensureIdempotency(payment, command);
        if (payment.status() != PaymentStatus.READY) {
            log.info(
                    "pay.payment.execution.prepare.replay status={}",
                    payment.status());
            return PreparedPaymentExecution.existing(payment);
        }
        if (!CONSUMED.equals(payment.credentialStatus())) {
            throw new PayException(
                    PayErrorCode.STATE_CONFLICT,
                    "승인 후 소비된 Credential에 연결된 Payment만 실행할 수 있습니다.",
                    payment.status().name(),
                    "RESTART_CHECKOUT");
        }
        if (payment.paymentMethodRef() == null || payment.paymentMethodRef().isBlank()) {
            throw new PayException(
                    PayErrorCode.PAYMENT_METHOD_NOT_FOUND,
                    PayErrorCode.PAYMENT_METHOD_NOT_FOUND.defaultMessage(),
                    payment.status().name(),
                    PayErrorCode.PAYMENT_METHOD_NOT_FOUND.defaultNextAction());
        }

        Instant now = clock.instant();
        String attemptId = UUID.randomUUID().toString();
        String pgRequestId = "pg-charge-" + UUID.randomUUID();
        String pgTransactionRecordId = UUID.randomUUID().toString();

        lifecycleService.markProcessing(payment.id());
        repository.insertChargeAttempt(
                attemptId,
                payment.id(),
                payment.requestHash(),
                pgRequestId,
                now);
        repository.insertPgTransaction(
                pgTransactionRecordId,
                attemptId,
                now);

        log.info(
                "pay.payment.execution.prepare.complete attemptId={} pgRequestId={} status=PROCESSING",
                attemptId,
                pgRequestId);

        return PreparedPaymentExecution.readyForGateway(
                payment.id(),
                attemptId,
                pgTransactionRecordId,
                new ChargeRequest(
                        payment.id(),
                        pgRequestId,
                        payment.paymentMethodRef(),
                        payment.amountMinor(),
                        payment.currency()));
    }

    private void validate(PaymentExecutionCommand command) {
        if (command == null) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        RequestValidation.requireLengthAtMost(command.paymentId(), "paymentId", 36);
        RequestValidation.requireLengthAtMost(command.idempotencyKey(), "idempotencyKey", 100);
        RequestValidation.requireLengthAtMost(command.requestHash(), "requestHash", 64);
    }

    private void ensureIdempotency(
            PaymentExecutionRecord payment,
            PaymentExecutionCommand command) {
        if (!payment.idempotencyKey().equals(command.idempotencyKey())) {
            throw idempotencyConflict(payment.status());
        }
        idempotencyGuard.check(
                payment.requestHash(),
                command.requestHash(),
                payment.status().name());
    }

    private PayException idempotencyConflict(PaymentStatus currentStatus) {
        return new PayException(
                PayErrorCode.IDEMPOTENCY_CONFLICT,
                PayErrorCode.IDEMPOTENCY_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.IDEMPOTENCY_CONFLICT.defaultNextAction());
    }
}
