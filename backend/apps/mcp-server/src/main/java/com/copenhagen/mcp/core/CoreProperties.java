package com.copenhagen.mcp.core;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("services.core")
public record CoreProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxResponseBytes) {

    public CoreProperties {
        if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))) {
            throw new IllegalArgumentException("Core base URL must be an HTTP(S) origin without credentials");
        }
        if (connectTimeout == null || connectTimeout.toMillis() <= 0
                || requestTimeout == null || requestTimeout.toMillis() <= 0 || maxResponseBytes <= 0) {
            throw new IllegalArgumentException("Core timeouts and response limit must be positive");
        }
    }
}
