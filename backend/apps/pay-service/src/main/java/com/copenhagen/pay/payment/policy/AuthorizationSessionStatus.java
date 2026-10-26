package com.copenhagen.pay.payment.policy;

public enum AuthorizationSessionStatus {

    PAYMENT_METHOD_REQUIRED,
    PENDING,
    APPROVED,
    COMPLETED,
    REJECTED,
    EXPIRED,
    INVALIDATED;

    public boolean isActive() {
        return this == PAYMENT_METHOD_REQUIRED
                || this == PENDING
                || this == APPROVED;
    }

    public boolean isTerminal() {
        return this == COMPLETED
                || this == REJECTED
                || this == EXPIRED
                || this == INVALIDATED;
    }
}
