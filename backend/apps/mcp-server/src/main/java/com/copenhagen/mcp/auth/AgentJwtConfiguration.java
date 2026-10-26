package com.copenhagen.mcp.auth;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentJwtProperties.class)
@ConditionalOnProperty(name = "mcp.auth.jwt.enabled", havingValue = "true")
public class AgentJwtConfiguration {
    @Bean
    McpTokenVerifier jwksMcpTokenVerifier(AgentJwtProperties properties) {
        return new JwksMcpTokenVerifier(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build(),
                Clock.systemUTC());
    }
}
