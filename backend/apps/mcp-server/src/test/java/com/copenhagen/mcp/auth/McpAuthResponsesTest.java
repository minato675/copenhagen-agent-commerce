package com.copenhagen.mcp.auth;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpAuthResponsesTest {
    @Test
    void toolBody_executionBoundary_preservesUncertaintyAfterHandlerStarted() {
        McpAuthResponses responses = new McpAuthResponses(null, null);
        McpAuthException error = new McpAuthException(McpAuthError.INSUFFICIENT_SCOPE);
        assertEquals("NOT_APPLIED", ((Map<?, ?>) responses.toolBody(error, true).get("error")).get("outcome"));
        assertEquals("UNKNOWN", ((Map<?, ?>) responses.toolBody(error, false).get("error")).get("outcome"));
    }
}
