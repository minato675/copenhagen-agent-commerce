package com.copenhagen.pay.payment.policy;

public enum PaymentStatus {

    READY,
    PROCESSING,
    SUCCESS,
    FAILED,
    UNKNOWN;

    public boolean isLive() {
        return this == READY
                || this == PROCESSING
                || this == SUCCESS
                || this == UNKNOWN;
    }
}
