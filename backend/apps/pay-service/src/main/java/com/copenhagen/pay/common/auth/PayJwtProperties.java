package com.copenhagen.pay.common.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pay.auth.jwt")
public record PayJwtProperties(
        boolean enabled,
        String issuer,
        String audience,
        String jwkSetJson,
        long clockSkewSeconds) {

    public PayJwtProperties {
        issuer = normalize(issuer);
        audience = normalize(audience);
        jwkSetJson = normalize(jwkSetJson);
        if (clockSkewSeconds < 0) {
            throw new IllegalArgumentException("JWT clock skew must not be negative");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
