package com.copenhagen.mcp.diagnostics;

import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DiagnosticConfiguration {
    @Bean
    DiagnosticSessions diagnosticSessions(@Value("${mcp.diagnostics.admin-user-refs:}") String configured,
            @Value("${mcp.diagnostics.enabled:false}") boolean enabled) {
        Set<String> admins = Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> UUID.fromString(s).toString()).collect(Collectors.toUnmodifiableSet());
        return new DiagnosticSessions(enabled ? admins : Set.of(), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "mcp.diagnostics.enabled", havingValue = "true")
    McpToolDefinition startResponseTiming(DiagnosticSessions sessions) {
        return tool("start_response_timing", "응답시간 체크 시작. 진단 관리자만 사용 가능합니다. "
                + "사용자가 응답시간 체크해줘라고 요청하면 호출하세요. 반환 세션은 이 대화에서만 사용하세요.",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                (identity, request) -> sessions.start(identity), false);
    }

    @Bean
    @ConditionalOnProperty(name = "mcp.diagnostics.enabled", havingValue = "true")
    McpToolDefinition getResponseTiming(DiagnosticSessions sessions) {
        return tool("get_response_timing", "관리자 측정 결과 조회. 측정 중 각 작업의 마지막에 호출해 "
                + "이번 작업에 해당하는 새 기록을 로그처럼 요약하세요. 서버 시간과 미측정 구간을 구분하고 "
                + "하위 시간을 전체 시간에 더하지 마세요. 세션 ID는 사용자에게 출력하지 마세요.",
                sessionSchema(), (identity, request) -> sessions.report(identity,
                        (String) request.arguments().get(DiagnosticSessions.FIELD), false), true);
    }

    @Bean
    @ConditionalOnProperty(name = "mcp.diagnostics.enabled", havingValue = "true")
    McpToolDefinition stopResponseTiming(DiagnosticSessions sessions) {
        return tool("stop_response_timing", "사용자가 측정 종료를 요청하면 최종 기록을 반환하고 세션을 삭제합니다.",
                sessionSchema(), (identity, request) -> sessions.report(identity,
                        (String) request.arguments().get(DiagnosticSessions.FIELD), true), false);
    }

    private Map<String, Object> sessionSchema() {
        return Map.of("type", "object", "properties", Map.of(DiagnosticSessions.FIELD,
                Map.of("type", "string", "format", "uuid")), "required", List.of(DiagnosticSessions.FIELD),
                "additionalProperties", false);
    }

    private McpToolDefinition tool(String name, String description, Map<String, Object> schema,
            java.util.function.BiFunction<com.copenhagen.mcp.auth.McpIdentity,
                    McpSchema.CallToolRequest, Map<String, Object>> handler, boolean readOnly) {
        return new McpToolDefinition(McpSchema.Tool.builder(name, schema).description(description)
                .outputSchema(Map.of("type", "object", "additionalProperties", true))
                .meta(Map.of("securitySchemes",
                        List.of(Map.of("type", "oauth2", "scopes", List.of()))))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly)
                        .destructiveHint(false).idempotentHint(readOnly).openWorldHint(false).build())
                .build(), handler);
    }
}
