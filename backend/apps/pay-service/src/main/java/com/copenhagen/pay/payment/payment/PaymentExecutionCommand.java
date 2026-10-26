package com.copenhagen.pay.payment.payment;

public record PaymentExecutionCommand(
        String paymentId,
        String idempotencyKey,
        String requestHash) {
}
