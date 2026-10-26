package com.copenhagen.mcp.diagnostics;

import com.copenhagen.mcp.auth.McpAuthError;
import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit session IDs prevent unrelated conversations from sharing measurements. */
public final class DiagnosticSessions {
    public static final String FIELD = "diagnosticSessionId";
    private static final ThreadLocal<List<Map<String, Object>>> STAGES = new ThreadLocal<>();
    private final Set<String> admins;
    private final Clock clock;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public DiagnosticSessions(Set<String> admins, Clock clock) {
        this.admins = Set.copyOf(admins);
        this.clock = clock;
    }

    private void authorize(McpIdentity identity) {
        if (!admins.contains(identity.publicUserRef()) || !clock.instant().isBefore(identity.expiresAt())) {
            throw new McpAuthException(McpAuthError.ACCESS_DENIED);
        }
    }

    public synchronized Map<String, Object> start(McpIdentity identity) {
        authorize(identity);
        sessions.values().removeIf(s -> !clock.instant().isBefore(s.expires));
        if (sessions.size() >= 100) {
            throw new IllegalStateException("Diagnostic capacity reached");
        }
        String id = UUID.randomUUID().toString();
        Session session = new Session(identity.publicUserRef(), identity.connectionRef(), clock.instant());
        sessions.put(id, session);
        return Map.of("diagnosticSessionId", id, "expiresAt", session.expires.toString(),
                "instruction", "이 대화의 이후 도구 호출에 diagnosticSessionId를 전달하고 "
                        + "작업 마지막에 get_response_timing을 호출하세요. 일반 대화와 GPT 생성 시간은 측정되지 않습니다.");
    }

    private synchronized Session owned(McpIdentity identity, String id) {
        authorize(identity);
        Session session = sessions.get(id);
        if (session == null) {
            throw new com.copenhagen.mcp.core.CoreClientException(
                    com.copenhagen.mcp.tool.ToolErrorCode.DIAGNOSTIC_SESSION_UNAVAILABLE);
        }
        if (!session.owner.equals(identity.publicUserRef())
                || !session.connection.equals(identity.connectionRef())) {
            throw new McpAuthException(McpAuthError.ACCESS_DENIED);
        }
        if (!clock.instant().isBefore(session.expires)) {
            throw new com.copenhagen.mcp.core.CoreClientException(
                    com.copenhagen.mcp.tool.ToolErrorCode.DIAGNOSTIC_SESSION_UNAVAILABLE);
        }
        return session;
    }

    public synchronized Map<String, Object> report(McpIdentity identity, String id, boolean stop) {
        Session session = owned(identity, id);
        Map<String, Object> result = Map.of("entries", List.copyOf(session.entries),
                "truncated", session.truncated, "stopped", stop,
                "coverage", "MCP handler and nested service_token/core_http only. Nested times overlap total; "
                        + "do not sum. GPT reasoning, final rendering, DB and merchant internals are not measured. "
                        + "Gaps include host/network/user time, not proven model latency.");
        if (stop) {
            sessions.remove(id);
        }
        return result;
    }

    public static void stage(String name, long started) {
        List<Map<String, Object>> stages = STAGES.get();
        if (stages != null && stages.size() < 100) {
            stages.add(Map.of("stage", name, "elapsedMs", (System.nanoTime() - started) / 1_000_000));
        }
    }

    @SuppressWarnings("unchecked")
    public McpToolDefinition instrument(McpToolDefinition definition) {
        if (admins.isEmpty() || definition.tool().name().endsWith("response_timing")) {
            return definition;
        }
        McpSchema.Tool original = definition.tool();
        Map<String, Object> schema = new LinkedHashMap<>(original.inputSchema());
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>)
                schema.getOrDefault("properties", Map.of()));
        properties.put(FIELD, Map.of("type", "string", "format", "uuid",
                "description", "관리자 측정 시작 도구가 반환한 이 대화의 세션 ID. 일반 사용자는 생략."));
        schema.put("properties", properties);
        McpSchema.Tool tool = new McpSchema.Tool(original.name(), original.title(), original.description()
                + " 관리자 측정 중이면 diagnosticSessionId를 유지하고 최종 작업 뒤 get_response_timing으로 결과를 보고하세요.",
                schema, original.outputSchema(), original.annotations(), original.meta(), original.icons());
        return new McpToolDefinition(tool, definition.requiredScopes(), (identity, request) -> {
            Map<String, Object> args = new LinkedHashMap<>(
                    request.arguments() == null ? Map.of() : request.arguments());
            Object id = args.remove(FIELD);
            if (id == null) {
                return definition.handler().apply(identity, request);
            }
            Session session = owned(identity, (String) id);
            long started = System.nanoTime();
            Instant at = clock.instant();
            List<Map<String, Object>> stages = new ArrayList<>();
            STAGES.set(stages);
            boolean failed = true;
            try {
                Map<String, Object> output = definition.handler().apply(identity,
                        new McpSchema.CallToolRequest(request.name(), args, request.meta()));
                failed = output.containsKey("error");
                return output;
            } finally {
                STAGES.remove();
                synchronized (this) {
                    if (session.entries.size() < 200) {
                        session.entries.add(Map.of("tool", original.name(), "startedAt", at.toString(),
                                "elapsedMs", (System.nanoTime() - started) / 1_000_000,
                                "failed", failed, "stages", List.copyOf(stages)));
                    } else {
                        session.truncated = true;
                    }
                }
            }
        });
    }

    private static final class Session {
        private final String owner;
        private final String connection;
        private final Instant expires;
        private final List<Map<String, Object>> entries = new ArrayList<>();
        private boolean truncated;

        Session(String owner, String connection, Instant started) {
            this.owner = owner;
            this.connection = connection;
            this.expires = started.plusSeconds(1800);
        }
    }
}
