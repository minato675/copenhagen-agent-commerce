package com.copenhagen.pay.payment.paymentmethod;

public record PaymentMethod(
        String id,
        String userId,
        String provider,
        String providerTokenRef,
        String brand,
        String last4,
        Integer expiryMonth,
        Integer expiryYear,
        PaymentMethodStatus status,
        boolean defaultMethod) {

    @Override
    public String toString() {
        return "PaymentMethod["
                + "id='" + id + '\''
                + ", userId='" + userId + '\''
                + ", provider='" + provider + '\''
                + ", brand='" + brand + '\''
                + ", last4='" + last4 + '\''
                + ", expiryMonth=" + expiryMonth
                + ", expiryYear=" + expiryYear
                + ", status=" + status
                + ", defaultMethod=" + defaultMethod
                + ']';
    }
}
