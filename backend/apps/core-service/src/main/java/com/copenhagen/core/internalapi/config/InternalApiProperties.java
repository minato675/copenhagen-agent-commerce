package com.copenhagen.core.internalapi.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 내부 API 설정(설계서 §10).
 */
@Component
public class InternalApiProperties {

    private final Duration handoffTtl;
    private final int defaultPageSize;

    public InternalApiProperties(
            @Value("${core.internal.handoff-ttl:PT30M}") Duration handoffTtl,
            @Value("${core.internal.default-page-size:10}") int defaultPageSize) {
        this.handoffTtl = handoffTtl;
        this.defaultPageSize = defaultPageSize;
    }

    public Duration handoffTtl() {
        return handoffTtl;
    }

    public int defaultPageSize() {
        return defaultPageSize;
    }
}
