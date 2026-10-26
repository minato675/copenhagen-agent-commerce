package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyKey;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.common.validation.RequestValidation;
import com.copenhagen.pay.payment.credential.mandate.CreatedPayment;
import com.copenhagen.pay.payment.credential.mandate.PaymentCredentialConsumptionService;
import com.copenhagen.pay.payment.merchant.MerchantAccount;
import com.copenhagen.pay.payment.merchant.MerchantAccountRepository;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Merchant 결제 요청을 Credential 소비와 단일 PG 실행으로 연결합니다. */
@Service
public class MerchantPaymentService {

    private static final String KRW = "KRW";
    private static final Logger log = LoggerFactory.getLogger(MerchantPaymentService.class);

    private final MerchantAccountRepository merchantAccountRepository;
    private final PaymentCredentialConsumptionService credentialConsumptionService;
    private final PaymentExecutionService paymentExecutionService;

    public MerchantPaymentService(
            MerchantAccountRepository merchantAccountRepository,
            PaymentCredentialConsumptionService credentialConsumptionService,
            PaymentExecutionService paymentExecutionService) {
        this.merchantAccountRepository = merchantAccountRepository;
        this.credentialConsumptionService = credentialConsumptionService;
        this.paymentExecutionService = paymentExecutionService;
    }

    /**
     * Credential 소비 트랜잭션이 종료된 뒤에만 PG를 호출하도록 이 메서드에는
     * 트랜잭션을 선언하지 않습니다.
     */
    public PaymentResult create(CreateCommand command) {
        validate(command);
        log.info(
                "pay.payment.create.start merchantId={} checkoutId={} amountMinor={} currency={}",
                command.merchantId(),
                command.businessCheckoutId(),
                command.amountMinor(),
                command.currency());
        MerchantAccount merchant = merchantAccountRepository
                .findActiveByMerchantId(command.merchantId())
                .orElseThrow(() -> new PayException(PayErrorCode.FORBIDDEN));

        CreatedPayment payment = credentialConsumptionService.consume(
                new PaymentCredentialConsumptionService.ConsumeCommand(
                        merchant.id(),
                        command.merchantId(),
                        command.businessCheckoutId(),
                        command.checkoutHash(),
                        command.amountMinor(),
                        command.currency(),
                        command.credentialId(),
                        command.idempotencyKey(),
                        command.requestHash()));
        PayLogContext.put(PayLogContext.PAYMENT_ID, payment.id());
        PayLogContext.put(PayLogContext.ORDER_ID, payment.purchaseRequestId());
        log.info("pay.payment.created status={}", payment.status());
        PaymentExecutionResult execution = paymentExecutionService.execute(
                new PaymentExecutionCommand(
                        payment.id(),
                        command.idempotencyKey(),
                        command.requestHash()));
        log.info(
                "pay.payment.create.complete status={}",
                execution.status());
        return new PaymentResult(payment, execution);
    }

    private void validate(CreateCommand command) {
        if (command == null) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        RequestValidation.requireLengthAtMost(command.merchantId(), "merchantId", 100);
        RequestValidation.requireLengthAtMost(
                command.businessCheckoutId(),
                "businessCheckoutId",
                150);
        RequestValidation.requireLengthAtMost(command.checkoutHash(), "checkoutHash", 100);
        RequestValidation.requireLengthAtMost(command.credentialId(), "credentialId", 36);
        RequestValidation.requireLengthAtMost(command.idempotencyKey(), "idempotencyKey", 100);
        RequestValidation.requireLengthAtMost(command.requestHash(), "requestHash", 64);
        if (command.amountMinor() < 0 || !KRW.equals(command.currency())) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "결제 금액 또는 통화가 올바르지 않습니다.",
                    null,
                    "CHECK_PAYMENT_AMOUNT");
        }

        if (!IdempotencyKey.isUuid(command.idempotencyKey())) {
            throw new PayException(PayErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
    }

    public record CreateCommand(
            String merchantId,
            String businessCheckoutId,
            String checkoutHash,
            long amountMinor,
            String currency,
            String credentialId,
            String idempotencyKey,
            String requestHash) {
    }

    public record PaymentResult(
            CreatedPayment payment,
            PaymentExecutionResult execution) {

        public PaymentStatus status() {
            return execution.status();
        }
    }
}
