package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.util.Optional;

/** Merchant 결제 상태 API가 필요한 최소 공개 조회 모델만 제공합니다. */
public interface MerchantPaymentQueryRepository {

    Optional<PaymentView> findByMerchantIdAndPaymentId(String merchantId, String paymentId);

    record PaymentView(
            String paymentId,
            PaymentStatus status,
            long amountMinor,
            String currency) {
    }
}
