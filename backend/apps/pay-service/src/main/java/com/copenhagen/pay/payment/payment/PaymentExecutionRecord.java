package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.policy.PaymentStatus;

/** Payment와 결제 실행 원장의 현재 상태를 함께 표현한 조회 모델입니다. */
public record PaymentExecutionRecord(
        String id,
        String authorizationSessionId,
        String merchantAccountId,
        String merchantId,
        String externalCheckoutId,
        String purchaseRequestId,
        String credentialTokenId,
        String credentialStatus,
        String paymentMethodRef,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String idempotencyKey,
        String requestHash,
        String attemptId,
        String pgRequestId,
        String attemptStatus,
        String pgTransactionId,
        String pgTransactionStatus,
        String pgResponseCode,
        String receiptId) {
}
