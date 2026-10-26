package com.copenhagen.mcp;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class McpAuthTestConfiguration {

    @Bean
    FakeMcpTokenVerifier fixtureTokenVerifier() {
        return new FakeMcpTokenVerifier();
    }
}
