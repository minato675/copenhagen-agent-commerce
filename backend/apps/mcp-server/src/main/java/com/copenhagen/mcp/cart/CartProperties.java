package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.auth.McpAuthProperties;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.cart")
public record CartProperties(boolean enabled, Set<String> readScopes, Set<String> writeScopes) {

    public CartProperties {
        readScopes = readScopes == null ? Set.of() : Set.copyOf(readScopes);
        writeScopes = writeScopes == null ? Set.of() : Set.copyOf(writeScopes);
        McpAuthProperties.validateScopes(readScopes);
        McpAuthProperties.validateScopes(writeScopes);
        if (enabled && (readScopes.isEmpty() || writeScopes.isEmpty() || readScopes.containsAll(writeScopes))) {
            throw new IllegalArgumentException("Cart requires explicit separate read and write scopes");
        }
    }
}
