package com.copenhagen.mcp.auth;

public enum McpAuthError {
    AUTHENTICATION_REQUIRED(401, "", "Connect your account to continue", "REAUTHENTICATE"),
    INVALID_TOKEN(401, "invalid_token", "Authentication is invalid or expired", "REAUTHENTICATE"),
    ACCOUNT_NOT_CONNECTED(401, "invalid_token", "Account connection is required", "RECONNECT_ACCOUNT"),
    INSUFFICIENT_SCOPE(403, "insufficient_scope", "Additional permission is required", "REQUEST_PERMISSION"),
    ACCESS_DENIED(403, "", "Access is not permitted", "CHECK_RESOURCE"),
    INVALID_AUTH_REQUEST(400, "invalid_request", "Invalid authentication request", "FIX_REQUEST"),
    AUTH_PROVIDER_UNAVAILABLE(503, "", "Authentication is temporarily unavailable", "RETRY_LATER"),
    AUTH_NOT_CONFIGURED(503, "", "Authentication integration is not configured", "CONTACT_OPERATOR"),
    SESSION_NOT_FOUND(404, "", "Start a new MCP session", "REINITIALIZE");

    private final int status;
    private final String oauthError;
    private final String message;
    private final String nextAction;

    McpAuthError(int status, String oauthError, String message, String nextAction) {
        this.status = status;
        this.oauthError = oauthError;
        this.message = message;
        this.nextAction = nextAction;
    }

    public int status() {
        return status;
    }

    public String oauthError() {
        return oauthError;
    }

    public String message() {
        return message;
    }

    public String nextAction() {
        return nextAction;
    }
}
