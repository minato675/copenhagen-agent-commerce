package com.copenhagen.mcp.handoff;

import com.copenhagen.mcp.cart.CartProperties;
import com.copenhagen.mcp.catalog.CatalogProperties;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(HandoffConfiguration.class)
            .withBean(JsonSchemaValidator.class, McpJsonDefaults::getSchemaValidator)
            .withBean(McpJsonMapper.class, McpJsonDefaults::getMapper)
            .withBean(CartProperties.class, () -> new CartProperties(true, Set.of("cart:read"), Set.of("cart:write")))
            .withBean(CatalogProperties.class, () -> new CatalogProperties(true, Set.of("catalog:read")));

    @Test
    void defaultDisabledAndMissingAdapterFailClosed() {
        context.run(app -> assertThat(app).hasNotFailed().doesNotHaveBean(McpToolDefinition.class));
        context.withPropertyValues("mcp.handoff.enabled=true", "mcp.handoff.write-scopes=handoff:write",
                "mcp.handoff.link-base=https://example.com/purchase").run(app -> assertThat(app).hasFailed());
    }

    @Test
    void readScopeUnionCannotAuthorizeHandoff() {
        context.withPropertyValues("mcp.handoff.enabled=true", "mcp.handoff.write-scopes=cart:read,catalog:read",
                "mcp.handoff.link-base=https://example.com/purchase")
                .withBean(CorePurchaseHandoff.class, () -> (identity, request) -> null)
                .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void explicitlyConfiguredAdapterExposesOnlyProtectedWriteTool() {
        context.withPropertyValues("mcp.handoff.enabled=true", "mcp.handoff.write-scopes=handoff:write",
                "mcp.handoff.link-base=https://example.com/purchase")
                .withBean(CorePurchaseHandoff.class, () -> (identity, request) -> null).run(app -> {
                    assertThat(app).hasNotFailed();
                    McpToolDefinition definition = app.getBean(McpToolDefinition.class);
                    assertThat(definition.requiredScopes()).containsExactly("handoff:write");
                    assertThat(definition.tool().annotations().readOnlyHint()).isFalse();
                    assertThat(definition.tool().annotations().idempotentHint()).isTrue();
                });
    }
}
