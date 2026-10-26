package com.copenhagen.mcp.auth;

import io.modelcontextprotocol.json.McpJsonMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpAuthProperties.class)
public class McpAuthConfiguration {

    @Bean
    McpAuthenticationService mcpAuthenticationService(
            McpAuthProperties properties, ObjectProvider<McpTokenVerifier> verifier) {
        return new McpAuthenticationService(properties, verifier::getIfAvailable, Clock.systemUTC());
    }

    @Bean
    McpAuthResponses mcpAuthResponses(McpAuthProperties properties, McpJsonMapper mapper) {
        return new McpAuthResponses(properties, mapper);
    }

    @Bean
    FilterRegistrationBean<McpAuthenticationFilter> mcpAuthenticationFilter(
            McpAuthenticationService authentication, McpAuthResponses responses) {
        FilterRegistrationBean<McpAuthenticationFilter> registration = new FilterRegistrationBean<>(
                new McpAuthenticationFilter(authentication, responses));
        registration.addUrlPatterns("/mcp");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(true);
        return registration;
    }
}
