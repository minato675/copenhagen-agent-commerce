package com.copenhagen.mcp.ui;

import com.copenhagen.mcp.auth.McpAuthProperties;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mcp.ui.enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!${mcp.integration.enabled:false}")
public class McpUiConfiguration {

    private final McpAuthProperties auth;

    public McpUiConfiguration(McpAuthProperties auth) {
        this.auth = auth;
    }

    private static final Map<String, Object> INPUT = Map.of(
            "type", "object", "properties", Map.of(), "additionalProperties", false);
    private static final Map<String, Object> OUTPUT = Map.of(
            "type", "object", "properties", Map.of(
                    "service", Map.of("type", "string", "const", "copenhagen-mcp"),
                    "status", Map.of("type", "string", "const", "connected"),
                    "checkedAt", Map.of("type", "string"),
                    "requestId", Map.of("type", "string"),
                    "uiVersion", Map.of("type", "string")),
            "required", List.of("service", "status", "checkedAt", "requestId", "uiVersion"),
            "additionalProperties", false);

    @Bean
    McpHomeResource mcpHomeResource() throws IOException {
        return new McpHomeResource();
    }

    @Bean
    SyncResourceSpecification mcpHomeSpecification(McpHomeResource home) {
        return home.specification();
    }

    @Bean
    @ConditionalOnProperty(name = {"mcp.catalog.enabled", "mcp.integration.enabled"},
            havingValue = "false", matchIfMissing = true)
    McpToolDefinition openCopenhagen(McpHomeResource home) {
        return tool("open_copenhagen", "코펜하겐 열기",
                "사용자가 코펜하겐을 열거나 연결을 확인하려고 하면 이 도구를 한 번 호출해 UI를 바로 표시합니다. "
                        + "현재는 MCP 연결 확인만 제공합니다. 상품 탐색·장바구니·결제는 제공하지 않습니다. "
                        + "필요한 짧은 안내는 호출 전에 하고, UI 이후에는 중복 설명을 덧붙이지 마세요.",
                Map.of("ui", Map.of("resourceUri", home.uri(), "visibility", List.of("model")),
                        "openai/outputTemplate", home.uri(), "openai/widgetAccessible", true,
                        "openai/toolInvocation/invoking", "연결 중", "openai/toolInvocation/invoked", ""), home);
    }

    @Bean
    McpToolDefinition checkConnection(McpHomeResource home) {
        return tool("check_connection", "연결 확인",
                "열린 UI의 버튼에서 MCP 왕복 연결을 확인합니다. Core 계정 또는 결제 연결의 성공을 뜻하지 않습니다.",
                Map.of("ui", Map.of("visibility", List.of("app")), "openai/widgetAccessible", true), home);
    }

    private McpToolDefinition tool(String name, String title, String description,
            Map<String, Object> metadata, McpHomeResource home) {
        Map<String, Object> securedMetadata = new LinkedHashMap<>(metadata);
        securedMetadata.put("securitySchemes", List.of(Map.of("type", "oauth2",
                "scopes", auth.requiredScopes().stream().sorted().toList())));
        return new McpToolDefinition(McpSchema.Tool.builder(name, INPUT)
                .title(title).description(description).outputSchema(OUTPUT).meta(securedMetadata)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build()).build(),
                auth.requiredScopes(),
                (identity, request) -> Map.of("service", "copenhagen-mcp", "status", "connected",
                        "checkedAt", Instant.now().toString(), "requestId", UUID.randomUUID().toString(),
                        "uiVersion", home.version()));
    }
}
