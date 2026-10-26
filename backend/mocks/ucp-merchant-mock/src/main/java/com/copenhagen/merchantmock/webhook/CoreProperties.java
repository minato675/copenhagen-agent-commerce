package com.copenhagen.merchantmock.webhook;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 core.* 설정. 구조는 DESIGN.md 3.4절이 소유한다.
 *
 * <p>{@code baseUrl} 은 Platform Profile 을 가져오는 데만 쓴다. 웹훅 주소는 Profile 에서 읽고,
 * {@code webhookUrlFallback} 은 Profile 을 못 읽거나 필드가 없을 때만 쓴다(8.6절).
 */
@ConfigurationProperties(prefix = "core")
public record CoreProperties(
        String baseUrl,
        String webhookUrlFallback,
        Duration profileCache,
        Duration connectTimeout,
        Duration requestTimeout) {

    public static final String WEBHOOK_PATH = "/webhooks/v1/ucp/orders";
    public static final Duration DEFAULT_PROFILE_CACHE = Duration.ofMinutes(5);
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(3);

    public CoreProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("core.base-url 이 비어 있다");
        }
        baseUrl = baseUrl.replaceAll("/+$", "");
        webhookUrlFallback = webhookUrlFallback == null || webhookUrlFallback.isBlank()
                ? baseUrl + WEBHOOK_PATH : webhookUrlFallback.trim();
        profileCache = profileCache == null ? DEFAULT_PROFILE_CACHE : profileCache;
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    }

    public String profileUrl() {
        return baseUrl + "/.well-known/ucp";
    }
}
