package com.copenhagen.mcp.integration;

import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mcp.integration.enabled", havingValue = "true")
public class WidgetActionConfiguration {
    @Bean
    WidgetActionStore widgetActionStore(McpJsonMapper mapper, JsonSchemaValidator validator) {
        return new WidgetActionStore(mapper, validator, Clock.systemUTC());
    }

    @Bean
    McpToolDefinition prepareWidgetAction(WidgetActionStore store) {
        var input = CommerceSchemas.object(Map.of("tool", Map.of("type", "string", "maxLength", 64),
                "arguments", Map.of("type", "object"),
                "intent", Map.of("type", "string", "minLength", 1, "maxLength", 10000)), List.of("tool", "arguments"));
        return new McpToolDefinition(McpSchema.Tool.builder("prepare_widget_action", input)
                .description("UI 선택 정보를 15분 동안 보관합니다. 실제 장바구니·결제 변경은 실행하지 않습니다.")
                .outputSchema(CommerceSchemas.OUTPUT)
                .meta(Map.of("ui", Map.of("visibility", List.of("app")),
                        "openai/visibility", "private", "openai/widgetAccessible", true))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false)
                        .idempotentHint(false).openWorldHint(false).build()).build(), Set.of(),
                (identity, request) -> store.prepare(identity, request.arguments()));
    }

    @Bean
    McpToolDefinition resolveWidgetAction(WidgetActionStore store) {
        return new McpToolDefinition(McpSchema.Tool.builder("resolve_widget_action",
                CommerceSchemas.object(Map.of("reference", CommerceSchemas.KEY,
                        "intent", Map.of("type", "string", "minLength", 1, "maxLength", 10000)), List.of()))
                .description("사용자가 UI에서 장바구니 변경사항 적용을 요청하면 먼저 이 도구로 저장된 선택을 조회하세요. "
                        + "copenhagenAction.reference가 있으면 reference를 사용하고, 없으면 사용자 요청 문장 전체를 "
                        + "intent로 그대로 전달하세요. 둘 중 하나만 입력하세요. 같은 계정·연결의 정확히 일치하는 선택 하나만 반환합니다. "
                        + "조회 성공은 변경 완료가 아닙니다. 반환된 tool과 arguments를 변경하지 않고 실행하세요. "
                        + "idempotencyKey를 새로 만들지 마세요. 변경 도구 성공 응답 뒤 display_cart를 한 번 호출하세요. "
                        + "get_cart만 호출하고 변경했다고 답하지 마세요. 선택을 확인할 수 없으면 최신 장바구니와 "
                        + "상품·판매처·옵션·최종 수량을 사용자에게 확인하세요. 오류 근거 없이 만료나 안전 차단을 단정하지 마세요.")
                .outputSchema(CommerceSchemas.OUTPUT)
                .meta(Map.of("ui", Map.of("visibility", List.of("model"))))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build()).build(), Set.of(),
                (identity, request) -> {
                    var args = request.arguments();
                    if (args.containsKey("reference") == args.containsKey("intent")) {
                        throw new com.copenhagen.mcp.core.CoreClientException(
                                com.copenhagen.mcp.tool.ToolErrorCode.INVALID_ARGUMENT);
                    }
                    return args.containsKey("reference")
                            ? store.resolve(identity, (String) args.get("reference"))
                            : store.resolveIntent(identity, (String) args.get("intent"));
                });
    }
}
