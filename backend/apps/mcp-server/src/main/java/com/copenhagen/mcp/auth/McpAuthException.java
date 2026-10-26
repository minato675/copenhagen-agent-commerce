package com.copenhagen.mcp.auth;

import java.util.Set;

public final class McpAuthException extends RuntimeException {

    private final McpAuthError error;
    private final Set<String> requiredScopes;

    public McpAuthException(McpAuthError error) {
        this(error, Set.of());
    }

    public McpAuthException(McpAuthError error, Set<String> requiredScopes) {
        super(error.message());
        this.error = error;
        this.requiredScopes = Set.copyOf(requiredScopes);
    }

    public McpAuthError error() {
        return error;
    }

    public Set<String> requiredScopes() {
        return requiredScopes;
    }
}
