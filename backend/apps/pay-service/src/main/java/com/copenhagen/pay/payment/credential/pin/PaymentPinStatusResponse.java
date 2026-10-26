package com.copenhagen.pay.payment.credential.pin;

public record PaymentPinStatusResponse(
        boolean registered,
        PaymentPinStatus status,
        String nextAction) {

    public PaymentPinStatusResponse {
        if (status == null || nextAction == null || nextAction.isBlank()) {
            throw new IllegalArgumentException("PIN status response is incomplete");
        }
    }

    public static PaymentPinStatusResponse notRegistered() {
        return new PaymentPinStatusResponse(
                false,
                PaymentPinStatus.NOT_REGISTERED,
                "REGISTER_PAYMENT_PIN");
    }

    public static PaymentPinStatusResponse from(PaymentPinCredential credential) {
        if (credential == null || credential.status() == null) {
            throw new IllegalArgumentException("PIN credential is required");
        }

        return switch (credential.status()) {
            case NOT_REGISTERED -> notRegistered();
            case ACTIVE -> new PaymentPinStatusResponse(true, PaymentPinStatus.ACTIVE, "NONE");
            case LOCKED -> new PaymentPinStatusResponse(true, PaymentPinStatus.LOCKED, "WAIT_AND_RETRY");
            case REVOKED -> new PaymentPinStatusResponse(true, PaymentPinStatus.REVOKED, "REAUTHENTICATE");
        };
    }
}
