package com.copenhagen.pay.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PayAuthPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "pay.auth.user-tokens-json={\"user-001\":\"user-token\"}",
                    "pay.auth.core-service-tokens-json={\"core-service\":\"core-token\"}",
                    "pay.auth.merchant-service-tokens-json={\"merchant-a\":\"merchant-token\"}");

    @Test
    void bindsEnvironmentJsonMappings() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            PayAuthProperties properties = context.getBean(PayAuthProperties.class);

            assertEquals(
                    "{\"user-001\":\"user-token\"}",
                    properties.userTokensJson());
            assertEquals(
                    "{\"core-service\":\"core-token\"}",
                    properties.coreServiceTokensJson());
            assertEquals(
                    "{\"merchant-a\":\"merchant-token\"}",
                    properties.merchantServiceTokensJson());
        });
    }

    @EnableConfigurationProperties(PayAuthProperties.class)
    static class TestConfiguration {
    }
}
