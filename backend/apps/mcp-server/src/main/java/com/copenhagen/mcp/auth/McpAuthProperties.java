package com.copenhagen.mcp.auth;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.auth")
public record McpAuthProperties(URI issuer, URI resource, Set<String> requiredScopes) {

    public McpAuthProperties {
        requiredScopes = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
        validateScopes(requiredScopes);
        validateUri(issuer);
        validateUri(resource);
        if (resource != null && !"/mcp".equals(resource.getRawPath())) {
            throw new IllegalArgumentException("MCP resource path must be /mcp");
        }
    }

    public boolean configured() {
        return issuer != null && resource != null && !requiredScopes.isEmpty();
    }

    public String metadataUrl() {
        return resource.resolve("/.well-known/oauth-protected-resource/mcp").toASCIIString();
    }

    public static void validateScopes(Set<String> scopes) {
        if (scopes.stream().anyMatch(scope -> !scope.matches("[A-Za-z0-9:._/-]+"))) {
            throw new IllegalArgumentException("MCP scopes must contain safe scope identifiers");
        }
    }

    private static void validateUri(URI uri) {
        if (uri == null) {
            return;
        }
        // 운영 식별자는 HTTPS로 고정하고, HTTP 예외는 명시적 loopback 주소에만 둔다.
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(
                uri.getHost() == null ? "" : uri.getHost());
        boolean secure = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback);
        if (!secure || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("MCP auth URL must be HTTPS or loopback HTTP without credentials");
        }
    }
}
