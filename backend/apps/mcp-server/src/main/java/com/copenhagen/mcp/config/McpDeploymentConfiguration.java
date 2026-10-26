package com.copenhagen.mcp.config;

import com.copenhagen.mcp.auth.McpAuthProperties;
import com.copenhagen.mcp.auth.McpTokenVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 배포 프로필에서 인증 준비 누락을 HTTP 요청 이전에 검출한다. */
@Configuration(proxyBeanMethods = false)
@Profile({"deployment", "oauth-registration"})
@EnableConfigurationProperties(McpAuthProperties.class)
public class McpDeploymentConfiguration {

    @Bean
    SmartInitializingSingleton mcpDeploymentValidation(
            McpAuthProperties auth, ObjectProvider<McpTokenVerifier> verifier) {
        return () -> {
            if (!auth.configured()) {
                throw new IllegalStateException("Deployment requires MCP auth issuer, resource and required scopes");
            }
            if (!"https".equals(auth.issuer().getScheme()) || !"https".equals(auth.resource().getScheme())) {
                throw new IllegalStateException("Deployment requires HTTPS auth issuer and resource");
            }
            if (verifier.getIfAvailable() == null) {
                throw new IllegalStateException("Deployment requires a configured McpTokenVerifier implementation");
            }
        };
    }
}
