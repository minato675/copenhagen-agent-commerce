package com.copenhagen.pay.common.auth;

public final class PayAuthenticationPolicy {

    private static final String APP_PREFIX = "/api/pay/v1";
    private static final String INTERNAL_PREFIX = "/internal/v1";
    private static final String MERCHANT_PREFIX = "/merchant/v1";

    private PayAuthenticationPolicy() {
    }

    public static RequiredPrincipal requiredPrincipal(String path) {
        if (path == null || path.isBlank()) {
            return RequiredPrincipal.NONE;
        }
        if (matches(path, APP_PREFIX)) {
            return RequiredPrincipal.USER;
        }
        if (matches(path, INTERNAL_PREFIX)) {
            return RequiredPrincipal.SERVICE;
        }
        if (matches(path, MERCHANT_PREFIX)) {
            return RequiredPrincipal.MERCHANT;
        }
        return RequiredPrincipal.NONE;
    }

    private static boolean matches(String path, String prefix) {
        return prefix.equals(path) || path.startsWith(prefix + "/");
    }

    public enum RequiredPrincipal {
        USER,
        SERVICE,
        MERCHANT,
        NONE
    }
}
