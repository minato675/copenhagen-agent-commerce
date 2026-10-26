package com.copenhagen.mcp.handoff;

import com.copenhagen.mcp.cart.CartProperties;
import com.copenhagen.mcp.catalog.CatalogProperties;
import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HandoffProperties.class)
@ConditionalOnProperty(name = "mcp.handoff.enabled", havingValue = "true")
public class HandoffConfiguration {
    @Bean
    PurchaseHandoffService purchaseHandoffService(CorePurchaseHandoff core, HandoffProperties properties,
            CartProperties cart, CatalogProperties catalog, JsonSchemaValidator validator, McpJsonMapper mapper) {
        java.util.Set<String> readScopes = new HashSet<>(cart.readScopes());
        readScopes.addAll(catalog.requiredScopes());
        if (!cart.enabled() || readScopes.containsAll(properties.writeScopes())) {
            throw new IllegalArgumentException("Handoff requires cart and a separate write scope");
        }
        return new PurchaseHandoffService(core, properties, validator, mapper, Clock.systemUTC());
    }

    @Bean
    McpToolDefinition preparePurchaseHandoff(PurchaseHandoffService service, HandoffProperties properties) {
        return new McpToolDefinition(McpSchema.Tool.builder("prepare_purchase_handoff", HandoffSchemas.INPUT)
                .title("모바일 앱 구매 인계 준비")
                .description("사용자가 결제 진행을 선택한 경우에만 현재 get_cart의 cartId/version으로 "
                        + "Core 인계를 요청합니다. 단일 판매처부터 지원합니다. 같은 논리 요청에는 키와 본문을 "
                        + "그대로 재사용하고 timeout/UNKNOWN 이후 새 키로 재생성하지 마세요. READY는 앱 인계 "
                        + "준비일 뿐 승인/결제 성공이 아닙니다. QA 링크는 실제 앱이 아닌 안내 문서를 엽니다.")
                .outputSchema(HandoffSchemas.OUTPUT).meta(Map.of("ui", Map.of("visibility", List.of("model", "app")),
                        "openai/widgetAccessible", true))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build()).build(), properties.writeScopes(),
                (identity, request) -> service.prepare(identity, request.arguments()));
    }
}
