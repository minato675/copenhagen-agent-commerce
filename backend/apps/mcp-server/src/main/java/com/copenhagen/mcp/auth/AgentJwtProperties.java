package com.copenhagen.mcp.auth;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.auth.jwt")
public record AgentJwtProperties(boolean enabled, URI jwksUri) {
    public AgentJwtProperties {
        if (enabled) {
            requireSecureUri(jwksUri);
        }
    }

    public static void requireSecureUri(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))) {
            throw new IllegalArgumentException("OAuth endpoint requires HTTPS or loopback HTTP");
        }
    }
}
