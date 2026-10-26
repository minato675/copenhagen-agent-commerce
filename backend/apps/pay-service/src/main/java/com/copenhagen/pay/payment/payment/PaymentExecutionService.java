package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.pg.PaymentGateway;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 결제 실행의 유일한 진입점입니다. PG 호출은 이 경계를 통해서만 수행합니다. */
@Service
public class PaymentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentExecutionService.class);

    private final PaymentExecutionPreparationService preparationService;
    private final PaymentExecutionSettlementService settlementService;
    private final PaymentGateway paymentGateway;

    public PaymentExecutionService(
            PaymentExecutionPreparationService preparationService,
            PaymentExecutionSettlementService settlementService,
            PaymentGateway paymentGateway) {
        this.preparationService = preparationService;
        this.settlementService = settlementService;
        this.paymentGateway = paymentGateway;
    }

    public PaymentExecutionResult execute(PaymentExecutionCommand command) {
        PreparedPaymentExecution prepared = preparationService.prepare(command);
        PayLogContext.put(PayLogContext.PAYMENT_ID, prepared.paymentId());
        if (!prepared.requiresGatewayCall()) {
            log.info(
                    "pay.payment.execution.replay paymentId={} status={}",
                    prepared.paymentId(),
                    prepared.existingPayment().status());
            return PaymentExecutionResult.from(prepared.existingPayment());
        }

        log.info(
                "pay.payment.execution.pg.start paymentId={} attemptId={}",
                prepared.paymentId(),
                prepared.attemptId());
        ChargeResult result;
        try {
            result = paymentGateway.charge(prepared.gatewayRequest());
        } catch (RuntimeException exception) {
            // PG 요청 도달 여부를 알 수 없으므로 예외를 실패로 단정하지 않습니다.
            log.error(
                    "pay.payment.execution.pg.adapter_failed paymentId={} attemptId={} exceptionType={}",
                    prepared.paymentId(),
                    prepared.attemptId(),
                    exception.getClass().getSimpleName());
            result = ChargeResult.unknown("PG_ADAPTER_ERROR");
        }
        log.info(
                "pay.payment.execution.pg.complete paymentId={} attemptId={} outcome={}",
                prepared.paymentId(),
                prepared.attemptId(),
                result.outcome());
        PaymentExecutionResult settled = settlementService.settle(prepared, result);
        log.info(
                "pay.payment.execution.complete paymentId={} status={}",
                prepared.paymentId(),
                settled.status());
        return settled;
    }
}
