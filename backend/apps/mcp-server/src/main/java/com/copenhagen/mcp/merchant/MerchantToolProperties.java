package com.copenhagen.mcp.merchant;

import com.copenhagen.mcp.auth.McpAuthProperties;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.merchants")
public record MerchantToolProperties(boolean enabled, Set<String> requiredScopes) {

    public MerchantToolProperties {
        requiredScopes = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
        McpAuthProperties.validateScopes(requiredScopes);
        if (enabled && requiredScopes.isEmpty()) {
            throw new IllegalArgumentException("Merchant Tool requires explicitly configured read scopes");
        }
    }
}
