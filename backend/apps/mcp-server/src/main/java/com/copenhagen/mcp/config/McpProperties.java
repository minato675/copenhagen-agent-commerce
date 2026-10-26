package com.copenhagen.mcp.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.server")
public record McpProperties(
        Duration requestTimeout,
        int maxRequestBytes,
        List<String> allowedOrigins,
        List<String> allowedHosts) {

    public McpProperties {
        if (requestTimeout == null || requestTimeout.toMillis() <= 0
                || maxRequestBytes <= 0 || maxRequestBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("MCP timeout and request limit must be positive");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
        allowedHosts = List.copyOf(allowedHosts);
        if (allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("MCP allowed hosts must not be empty");
        }
    }
}
