package com.copenhagen.mcp.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiagnosticSessionsTest {
    private final Instant now = Instant.parse("2026-09-22T00:00:00Z");
    private final McpIdentity admin = new McpIdentity("admin", "connection", Set.of(), now.plusSeconds(3600));
    private final DiagnosticSessions sessions = new DiagnosticSessions(Set.of("admin"),
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void enforcesAdminOwnerConnectionAndStop() {
        McpIdentity other = new McpIdentity("other", "connection", Set.of(), now.plusSeconds(3600));
        assertThrows(McpAuthException.class, () -> sessions.start(other));
        String id = (String) sessions.start(admin).get(DiagnosticSessions.FIELD);
        assertThrows(McpAuthException.class, () -> sessions.report(other, id, false));
        McpIdentity reconnect = new McpIdentity("admin", "new", Set.of(), now.plusSeconds(3600));
        assertThrows(McpAuthException.class, () -> sessions.report(reconnect, id, false));
        assertEquals(true, sessions.report(admin, id, true).get("stopped"));
        assertEquals(com.copenhagen.mcp.tool.ToolErrorCode.DIAGNOSTIC_SESSION_UNAVAILABLE,
                assertThrows(com.copenhagen.mcp.core.CoreClientException.class,
                        () -> sessions.report(admin, id, false)).code());
    }

    @Test
    void isolatesSessionsStripsControlInputAndCapturesFailure() {
        String first = (String) sessions.start(admin).get(DiagnosticSessions.FIELD);
        String second = (String) sessions.start(admin).get(DiagnosticSessions.FIELD);
        McpToolDefinition tool = sessions.instrument(new McpToolDefinition(McpSchema.Tool.builder("example",
                Map.of("type", "object", "properties", Map.of())).outputSchema(Map.of("type", "object")).build(),
                (identity, request) -> {
                    assertFalse(request.arguments().containsKey(DiagnosticSessions.FIELD));
                    DiagnosticSessions.stage("core_http", System.nanoTime());
                    throw new IllegalStateException("private response must not be recorded");
                }));
        assertThrows(IllegalStateException.class, () -> tool.handler().apply(admin,
                new McpSchema.CallToolRequest("example", Map.of(DiagnosticSessions.FIELD, first))));
        Map<String, Object> report = sessions.report(admin, first, false);
        assertEquals(1, ((List<?>) report.get("entries")).size());
        assertTrue(report.toString().contains("failed=true"));
        assertFalse(report.toString().contains("private response"));
        assertEquals(List.of(), sessions.report(admin, second, false).get("entries"));
        DiagnosticSessions.stage("outside", System.nanoTime());
        assertEquals(report, sessions.report(admin, first, false));
    }

    @Test
    void expiresAndBoundsRecords() {
        Clock moving = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(moving.instant()).thenReturn(now);
        DiagnosticSessions timed = new DiagnosticSessions(Set.of("admin"), moving);
        String id = (String) timed.start(admin).get(DiagnosticSessions.FIELD);
        McpToolDefinition tool = timed.instrument(new McpToolDefinition(McpSchema.Tool.builder("example",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .outputSchema(Map.of("type", "object")).build(), (identity, request) -> Map.of("ok", true)));
        Map<String, Object> args = Map.of(DiagnosticSessions.FIELD, id);
        assertTrue(io.modelcontextprotocol.json.McpJsonDefaults.getSchemaValidator()
                .validate(tool.tool().inputSchema(), args).valid());
        for (int i = 0; i < 201; i++) {
            tool.handler().apply(admin, new McpSchema.CallToolRequest("example", args));
        }
        assertEquals(200, ((List<?>) timed.report(admin, id, false).get("entries")).size());
        assertEquals(true, timed.report(admin, id, false).get("truncated"));
        org.mockito.Mockito.when(moving.instant()).thenReturn(now.plusSeconds(1800));
        assertEquals(com.copenhagen.mcp.tool.ToolErrorCode.DIAGNOSTIC_SESSION_UNAVAILABLE,
                assertThrows(com.copenhagen.mcp.core.CoreClientException.class,
                        () -> timed.report(admin, id, false)).code());
    }

    @Test
    void disabledConfigurationDoesNotModifyTools() {
        DiagnosticSessions disabled = new DiagnosticSessions(Set.of(), Clock.systemUTC());
        McpToolDefinition tool = new McpToolDefinition(McpSchema.Tool.builder("example",
                Map.of("type", "object")).outputSchema(Map.of("type", "object")).build(),
                (identity, request) -> Map.of());
        assertEquals(tool, disabled.instrument(tool));
        assertThrows(McpAuthException.class, () -> disabled.start(admin));
    }
}
