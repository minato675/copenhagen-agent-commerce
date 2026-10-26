package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.auth.McpAuthProperties;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.catalog")
public record CatalogProperties(boolean enabled, Set<String> requiredScopes) {

    public CatalogProperties {
        requiredScopes = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
        McpAuthProperties.validateScopes(requiredScopes);
        if (enabled && requiredScopes.isEmpty()) {
            throw new IllegalArgumentException("Catalog tools require explicitly configured read scopes");
        }
    }
}
