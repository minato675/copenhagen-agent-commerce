package com.copenhagen.pay.payment.pg;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services.pg")
public record PgProperties(
        String baseUrl,
        long connectTimeoutMillis,
        long requestTimeoutMillis) {

    public PgProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("services.pg.base-url must not be blank");
        }
        if (connectTimeoutMillis <= 0 || requestTimeoutMillis <= 0) {
            throw new IllegalArgumentException("PG timeout values must be positive");
        }
    }
}
