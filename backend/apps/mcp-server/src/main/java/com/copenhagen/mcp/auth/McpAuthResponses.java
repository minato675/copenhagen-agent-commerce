package com.copenhagen.mcp.auth;

import io.modelcontextprotocol.json.McpJsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class McpAuthResponses {

    private final McpAuthProperties properties;
    private final McpJsonMapper mapper;

    public McpAuthResponses(McpAuthProperties properties, McpJsonMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public void reject(HttpServletResponse response, McpAuthException exception) throws IOException {
        response.setStatus(exception.error().status());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        if (exception.error().status() == 401 || exception.error() == McpAuthError.INSUFFICIENT_SCOPE
                || exception.error() == McpAuthError.INVALID_AUTH_REQUEST) {
            response.setHeader("WWW-Authenticate", challenge(exception));
        }
        response.getWriter().write(mapper.writeValueAsString(body(exception)));
    }

    public Map<String, Object> body(McpAuthException exception) {
        return Map.of("error", Map.of("code", exception.error().name(), "message", exception.error().message(),
                "nextAction", exception.error().nextAction()));
    }

    public Map<String, Object> toolMetadata(McpAuthException exception) {
        if (exception.error().status() == 401 || exception.error() == McpAuthError.INSUFFICIENT_SCOPE) {
            return Map.of("mcp/www_authenticate", List.of(challenge(exception)));
        }
        return Map.of();
    }

    public Map<String, Object> toolBody(McpAuthException exception, boolean rejectedBeforeExecution) {
        return Map.of("error", Map.of("code", exception.error().name(), "message", exception.error().message(),
                "nextAction", exception.error().nextAction(),
                "outcome", rejectedBeforeExecution ? "NOT_APPLIED" : "UNKNOWN"));
    }

    private String challenge(McpAuthException exception) {
        StringBuilder challenge = new StringBuilder("Bearer");
        String separator = " ";
        if (properties.configured()) {
            challenge.append(separator).append("resource_metadata=\"").append(properties.metadataUrl()).append("\"");
            separator = ", ";
            Set<String> scopes = Stream.concat(properties.requiredScopes().stream(),
                    exception.requiredScopes().stream()).collect(Collectors.toUnmodifiableSet());
            McpAuthProperties.validateScopes(scopes);
            challenge.append(separator).append("scope=\"")
                    .append(String.join(" ", scopes.stream().sorted().toList())).append("\"");
        }
        if (!exception.error().oauthError().isEmpty()) {
            challenge.append(separator).append("error=\"").append(exception.error().oauthError()).append("\"");
        }
        return challenge.toString();
    }
}
