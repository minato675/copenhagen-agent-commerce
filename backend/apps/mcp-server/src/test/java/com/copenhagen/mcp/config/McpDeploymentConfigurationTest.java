package com.copenhagen.mcp.config;

import com.copenhagen.mcp.auth.McpTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class McpDeploymentConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(McpDeploymentConfiguration.class);

    private ApplicationContextRunner deployment() {
        return context.withPropertyValues("spring.profiles.active=deployment");
    }

    private ApplicationContextRunner configured() {
        return deployment().withPropertyValues("mcp.auth.issuer=https://auth.example.test",
                "mcp.auth.resource=https://mcp.example.test/mcp", "mcp.auth.required-scopes=mcp:access");
    }

    @Test
    void defaultStartupDoesNotRequireDeploymentCredentials() {
        context.run(app -> assertThat(app).hasNotFailed().doesNotHaveBean("mcpDeploymentValidation"));
    }

    @Test
    void registrationRejectsMissingAuthSettingsBeforeAcceptingRequests() {
        context.withPropertyValues("spring.profiles.active=oauth-registration")
                .run(app -> assertThat(app.getStartupFailure()).hasMessageContaining("requires MCP auth"));
    }

    @Test
    void deploymentRejectsMissingAndPartialAuthSettings() {
        deployment().run(app -> assertThat(app.getStartupFailure())
                .hasMessageContaining("requires MCP auth"));
        deployment().withPropertyValues("mcp.auth.issuer=https://auth.example.test")
                .run(app -> assertThat(app.getStartupFailure()).hasMessageContaining("requires MCP auth"));
    }

    @Test
    void deploymentRequiresVerifierEvenWithCompleteMetadata() {
        configured().run(app -> assertThat(app.getStartupFailure())
                .hasMessageContaining("McpTokenVerifier"));
    }

    @Test
    void deploymentRejectsLocalHttpAuthIdentifiers() {
        configured().withPropertyValues("mcp.auth.resource=http://localhost:8080/mcp")
                .withBean(McpTokenVerifier.class, () -> token -> null)
                .run(app -> assertThat(app.getStartupFailure()).hasMessageContaining("requires HTTPS"));
    }

    @Test
    void configuredDeploymentDoesNotCallVerifierAtStartup() {
        configured().withBean(McpTokenVerifier.class, () -> token -> {
            throw new AssertionError("Startup must not send a token to the provider");
        }).run(app -> assertThat(app).hasNotFailed().hasBean("mcpDeploymentValidation"));
    }
}
