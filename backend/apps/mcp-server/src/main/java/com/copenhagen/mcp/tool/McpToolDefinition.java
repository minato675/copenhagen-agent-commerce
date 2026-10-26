package com.copenhagen.mcp.tool;

import com.copenhagen.mcp.auth.McpAuthProperties;
import com.copenhagen.mcp.auth.McpIdentity;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/** 업무 Tool은 인증·Core 계약이 확정된 후 이 정의를 Spring bean으로 등록한다. */
public record McpToolDefinition(
        McpSchema.Tool tool,
        Set<String> requiredScopes,
        BiFunction<McpIdentity, McpSchema.CallToolRequest, Map<String, Object>> handler) {

    public McpToolDefinition {
        Objects.requireNonNull(tool);
        Objects.requireNonNull(handler);
        Objects.requireNonNull(tool.inputSchema(), "Tool input schema is required");
        Objects.requireNonNull(tool.outputSchema(), "Tool output schema is required");
        requiredScopes = Set.copyOf(requiredScopes);
        McpAuthProperties.validateScopes(requiredScopes);
    }

    public McpToolDefinition(McpSchema.Tool tool,
            BiFunction<McpIdentity, McpSchema.CallToolRequest, Map<String, Object>> handler) {
        this(tool, Set.of(), handler);
    }
}
