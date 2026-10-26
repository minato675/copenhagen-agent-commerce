package com.copenhagen.mcp.merchant;

import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantToolConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MerchantToolConfiguration.class)
            .withBean(JsonSchemaValidator.class, McpJsonDefaults::getSchemaValidator);

    @Test
    void configure_disabled_doesNotAdvertiseUnimplementedIntegration() {
        context.run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(McpToolDefinition.class));
    }

    @Test
    void configure_enabledWithoutAdapter_failsInsteadOfReturningFakeShops() {
        context.withPropertyValues("mcp.merchants.enabled=true", "mcp.merchants.required-scopes=fixture:shops:read")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void configure_enabledWithoutReadScopes_failsInsteadOfGrantingImplicitAccess() {
        context.withPropertyValues("mcp.merchants.enabled=true")
                .withBean(CoreMerchantDirectory.class, this::emptyDirectory)
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void configure_enabledWithAdapterAndScope_registersToolWithRequiredPermission() {
        context.withPropertyValues("mcp.merchants.enabled=true", "mcp.merchants.required-scopes=fixture:shops:read")
                .withBean(CoreMerchantDirectory.class, this::emptyDirectory)
                .run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(McpToolDefinition.class);
                    assertThat(application.getBean(McpToolDefinition.class).requiredScopes())
                            .containsExactly("fixture:shops:read");
                });
    }

    private CoreMerchantDirectory emptyDirectory() {
        return new CoreMerchantDirectory() {
            @Override
            public Map<String, Set<Capability>> registeredCapabilities() {
                return Map.of();
            }

            @Override
            public Map<String, Object> list(McpIdentity identity) {
                throw new UnsupportedOperationException("Configuration test must not call the directory");
            }
        };
    }
}
