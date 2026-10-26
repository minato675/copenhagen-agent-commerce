package com.copenhagen.core.payment;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code services.pay} 설정(이슈 410). {@code serviceToken}은 pay-service의
 * {@code PAY_AUTH_CORE_SERVICE_TOKENS_JSON}에 등록된 {@code core-service} 항목의 토큰 값과 정확히 같아야
 * 한다 — 다르면 Pay가 모든 내부 호출을 401로 거부한다.
 */
@ConfigurationProperties("services.pay")
public record PayServiceProperties(
        URI baseUrl,
        String serviceToken,
        Duration connectTimeout,
        Duration requestTimeout) {

    public PayServiceProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
    }
}
