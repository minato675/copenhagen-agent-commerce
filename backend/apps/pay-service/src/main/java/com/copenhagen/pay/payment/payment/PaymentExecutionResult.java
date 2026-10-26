package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.policy.PaymentStatus;

public record PaymentExecutionResult(
        String paymentId,
        PaymentStatus status,
        String attemptId,
        String pgTransactionId,
        String receiptId,
        String nextAction) {

    public static PaymentExecutionResult from(PaymentExecutionRecord payment) {
        return new PaymentExecutionResult(
                payment.id(),
                payment.status(),
                payment.attemptId(),
                payment.pgTransactionId(),
                payment.receiptId(),
                nextAction(payment.status()));
    }

    private static String nextAction(PaymentStatus status) {
        return switch (status) {
            case READY -> "EXECUTE_PAYMENT";
            case PROCESSING -> "CHECK_PAYMENT_STATUS";
            case SUCCESS -> "CREATE_ORDER";
            case FAILED -> "RESTART_CHECKOUT";
            case UNKNOWN -> "WAIT_FOR_RECONCILIATION";
        };
    }
}
