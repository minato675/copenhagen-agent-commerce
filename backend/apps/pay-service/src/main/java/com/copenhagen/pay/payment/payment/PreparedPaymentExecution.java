package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeRequest;

public record PreparedPaymentExecution(
        String paymentId,
        String attemptId,
        String pgTransactionRecordId,
        ChargeRequest gatewayRequest,
        PaymentExecutionRecord existingPayment) {

    public static PreparedPaymentExecution existing(PaymentExecutionRecord payment) {
        return new PreparedPaymentExecution(
                payment.id(),
                null,
                null,
                null,
                payment);
    }

    public static PreparedPaymentExecution readyForGateway(
            String paymentId,
            String attemptId,
            String pgTransactionRecordId,
            ChargeRequest gatewayRequest) {
        return new PreparedPaymentExecution(
                paymentId,
                attemptId,
                pgTransactionRecordId,
                gatewayRequest,
                null);
    }

    public boolean requiresGatewayCall() {
        return gatewayRequest != null;
    }
}
