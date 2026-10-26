package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.auth.PayPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

public record IdempotencyScope(
        String callerType,
        String callerId,
        String serviceType,
        String httpMethod,
        String normalizedPath,
        String idempotencyKey) {

    public IdempotencyScope {
        callerType = require(callerType, "callerType");
        callerId = require(callerId, "callerId");
        serviceType = require(serviceType, "serviceType");
        httpMethod = require(httpMethod, "httpMethod").toUpperCase(Locale.ROOT);
        normalizedPath = normalizePath(normalizedPath);
        idempotencyKey = require(idempotencyKey, "idempotencyKey");
    }

    public static IdempotencyScope from(
            HttpServletRequest request,
            PayPrincipal principal,
            IdempotencyKey idempotencyKey) {
        return new IdempotencyScope(
                principal.type().name(),
                principal.subject(),
                principal.serviceType().name(),
                request.getMethod(),
                request.getRequestURI(),
                idempotencyKey.value());
    }

    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }

        StringBuilder normalized = new StringBuilder(path.length());
        boolean previousSlash = false;
        for (char character : path.trim().toCharArray()) {
            if (character == '/') {
                if (previousSlash) {
                    continue;
                }
                previousSlash = true;
            } else {
                previousSlash = false;
            }
            normalized.append(character);
        }
        if (normalized.length() > 1 && normalized.charAt(normalized.length() - 1) == '/') {
            normalized.deleteCharAt(normalized.length() - 1);
        }
        return normalized.toString();
    }

    private static String require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
