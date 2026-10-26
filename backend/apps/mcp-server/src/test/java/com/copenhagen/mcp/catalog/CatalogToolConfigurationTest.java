package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogToolConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CatalogToolConfiguration.class)
            .withBean(JsonSchemaValidator.class, McpJsonDefaults::getSchemaValidator)
            .withBean(McpJsonMapper.class, McpJsonDefaults::getMapper);

    @Test
    void configure_defaultDoesNotExposeCatalog() {
        context.run(application -> assertThat(application).hasNotFailed().doesNotHaveBean(McpToolDefinition.class));
    }

    @Test
    void configure_requiresAdapterAndScope() {
        context.withPropertyValues("mcp.catalog.enabled=true", "mcp.catalog.required-scopes=qa:catalog:read")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("mcp.catalog.enabled=true")
                .withBean(CoreProductCatalog.class, () -> (identity, request) -> null)
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void configure_enabledToolRequiresExplicitReadScope() {
        context.withPropertyValues("mcp.catalog.enabled=true", "mcp.catalog.required-scopes=qa:catalog:read")
                .withBean(CoreProductCatalog.class, () -> (identity, request) -> null)
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(McpToolDefinition.class).requiredScopes())
                            .containsExactly("qa:catalog:read");
                });
    }
}
