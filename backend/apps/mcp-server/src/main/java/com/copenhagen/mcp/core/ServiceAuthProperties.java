package com.copenhagen.mcp.core;

import com.copenhagen.mcp.auth.AgentJwtProperties;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("services.core.auth")
public record ServiceAuthProperties(boolean enabled, URI tokenUri, String clientId, Path privateJwkFile) {
    public ServiceAuthProperties {
        if (enabled) {
            AgentJwtProperties.requireSecureUri(tokenUri);
            if (!"copenhagen-mcp".equals(clientId) || privateJwkFile == null) {
                throw new IllegalArgumentException("Core service client and private key file are required");
            }
        }
    }
}
