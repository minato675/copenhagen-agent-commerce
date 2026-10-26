package com.copenhagen.mcp.core;

import io.modelcontextprotocol.json.McpJsonMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceAuthProperties.class)
@ConditionalOnProperty(name = "services.core.auth.enabled", havingValue = "true")
public class ServiceAuthConfiguration {
    @Bean
    ServiceTokenProvider coreServiceTokenProvider(ServiceAuthProperties properties, HttpClient coreHttpClient,
            McpJsonMapper mapper) {
        return new CoreServiceTokenProvider(properties, coreHttpClient, mapper, Clock.systemUTC());
    }
}
