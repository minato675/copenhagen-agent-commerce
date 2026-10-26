package com.copenhagen.mcp.handoff;

import com.copenhagen.mcp.auth.McpAuthProperties;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mcp.handoff")
public record HandoffProperties(boolean enabled, Set<String> writeScopes, URI linkBase) {
    public HandoffProperties {
        writeScopes = writeScopes == null ? Set.of() : Set.copyOf(writeScopes);
        McpAuthProperties.validateScopes(writeScopes);
        if (enabled && (writeScopes.isEmpty() || linkBase == null || !"https".equals(linkBase.getScheme())
                || linkBase.getHost() == null || linkBase.getRawUserInfo() != null || linkBase.getPort() != -1
                || linkBase.getRawQuery() != null || linkBase.getRawFragment() != null
                || !linkBase.normalize().equals(linkBase) || linkBase.toString().contains("%"))) {
            throw new IllegalArgumentException("Handoff requires write scopes and a fixed HTTPS link base");
        }
    }
}
