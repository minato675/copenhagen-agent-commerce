package com.copenhagen.core.ucp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("core.ucp")
public record CoreUcpProperties(String publicBaseUrl, Duration merchantProfileCache) {

    public static final String UCP_VERSION = "2026-08-25";
    public static final String PROFILE_PATH = "/.well-known/ucp";
    public static final String ORDER_WEBHOOK_PATH = "/webhooks/v1/ucp/orders";

    public CoreUcpProperties {
        publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank()
                ? "http://core-service:8080" : publicBaseUrl.replaceAll("/+$", "");
        merchantProfileCache = merchantProfileCache == null ? Duration.ofMinutes(5) : merchantProfileCache;
    }

    public String profileUrl() {
        return publicBaseUrl + PROFILE_PATH;
    }

    public String orderWebhookUrl() {
        return publicBaseUrl + ORDER_WEBHOOK_PATH;
    }
}
