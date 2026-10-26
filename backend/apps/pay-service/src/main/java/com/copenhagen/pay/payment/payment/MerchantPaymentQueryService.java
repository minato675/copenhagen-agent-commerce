package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.validation.RequestValidation;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Merchant 결제 상태를 소유 범위 안에서 읽기만 하는 서비스입니다. */
@Service
public class MerchantPaymentQueryService {

    private static final int PAYMENT_ID_MAX_LENGTH = 36;

    private final MerchantPaymentQueryRepository repository;

    public MerchantPaymentQueryService(MerchantPaymentQueryRepository repository) {
        this.repository = repository;
    }

    public MerchantPaymentQueryRepository.PaymentView find(
            String merchantId,
            String paymentId) {
        String validatedMerchantId = RequestValidation.requireLengthAtMost(
                merchantId,
                "merchantId",
                100);
        String normalizedPaymentId = normalizePaymentId(paymentId);
        return repository.findByMerchantIdAndPaymentId(
                        validatedMerchantId,
                        normalizedPaymentId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
    }

    private String normalizePaymentId(String paymentId) {
        String validatedPaymentId = RequestValidation.requireLengthAtMost(
                paymentId,
                "paymentId",
                PAYMENT_ID_MAX_LENGTH);
        try {
            UUID uuid = UUID.fromString(validatedPaymentId);
            if (!uuid.toString().equalsIgnoreCase(validatedPaymentId)) {
                throw invalidPaymentId();
            }
            return uuid.toString();
        } catch (IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "paymentId 형식이 올바르지 않습니다.",
                    null,
                    "CHECK_PAYMENT_ID",
                    exception);
        }
    }

    private PayException invalidPaymentId() {
        return new PayException(
                PayErrorCode.INVALID_REQUEST,
                "paymentId 형식이 올바르지 않습니다.",
                null,
                "CHECK_PAYMENT_ID");
    }
}
