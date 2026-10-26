package com.copenhagen.pay.common.auth;

public record PayPrincipal(Type type, String subject, ServiceType serviceType) {

    public PayPrincipal(Type type, String subject) {
        this(
                type,
                subject,
                type == Type.USER ? ServiceType.NONE : ServiceType.UNKNOWN);
    }

    public PayPrincipal {
        if (type == null || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Authentication principal is incomplete");
        }
        if (serviceType == null) {
            serviceType = ServiceType.NONE;
        }
        if (type == Type.USER && serviceType != ServiceType.NONE) {
            throw new IllegalArgumentException("User principal cannot have a service type");
        }
        if (type == Type.SERVICE && serviceType == ServiceType.NONE) {
            throw new IllegalArgumentException("Service principal requires a service type");
        }
    }

    public boolean isUser() {
        return type == Type.USER;
    }

    public boolean isCoreService() {
        return type == Type.SERVICE && serviceType == ServiceType.CORE_SERVICE;
    }

    public boolean isMerchantService() {
        return type == Type.SERVICE && serviceType == ServiceType.MERCHANT_SERVICE;
    }

    public enum Type {
        USER,
        SERVICE
    }

    public enum ServiceType {
        NONE,
        UNKNOWN,
        CORE_SERVICE,
        MERCHANT_SERVICE
    }
}
