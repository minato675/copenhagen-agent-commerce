package com.copenhagen.pgmock;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pg.mock")
public record PgMockProperties(
        PgMockOutcome outcome,
        long responseDelayMillis,
        PgMockOutcome lookupOutcome) {

    public PgMockProperties {
        outcome = outcome == null ? PgMockOutcome.SUCCESS : outcome;
        lookupOutcome = lookupOutcome == null ? PgMockOutcome.UNKNOWN : lookupOutcome;
        if (responseDelayMillis < 0) {
            throw new IllegalArgumentException("pg.mock.response-delay-millis must not be negative");
        }
    }
}
