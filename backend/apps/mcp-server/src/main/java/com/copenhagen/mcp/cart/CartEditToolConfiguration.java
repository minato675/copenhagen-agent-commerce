package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mcp.cart.enabled", havingValue = "true")
public class CartEditToolConfiguration {

    @Bean
    CartEditService cartEditService(CoreCartEdits core, CartService boundary) {
        return new CartEditService(core, boundary);
    }

    @Bean
    McpToolDefinition updateCartItem(CartEditService service, CartProperties properties) {
        return definition(service, properties, true);
    }

    @Bean
    McpToolDefinition deleteCartItem(CartEditService service, CartProperties properties) {
        return definition(service, properties, false);
    }

    private McpToolDefinition definition(CartEditService service, CartProperties properties, boolean update) {
        return new McpToolDefinition(McpSchema.Tool.builder(update ? "update_cart_item" : "delete_cart_item",
                        update ? CartEditSchemas.UPDATE_INPUT : CartEditSchemas.DELETE_INPUT)
                .title(update ? "장바구니 수량·옵션 변경" : "장바구니 항목 삭제")
                .description("사용자가 요청한 장바구니 항목을 Core에서 변경합니다. get_cart의 cartId, lineId, "
                        + "version을 사용하세요. 수량은 증감량이 아닌 변경 후 총수량입니다. 옵션은 실제 상세 응답의 "
                        + "optionId만 사용합니다. 같은 논리 요청의 재시도는 키와 본문을 그대로 유지하고, "
                        + "타임아웃/오류/UNKNOWN 이후 새 키로 변경하지 마세요. 충돌은 다시 조회하고 사용자에게 "
                        + "확인하세요. APPLIED만 반영 완료입니다. 모든 변경은 기존 주문 승인 대상의 재검증·재승인을 "
                        + "요구하며 이 도구로 결제·주문을 실행하지 않습니다.")
                .outputSchema(update ? CartEditSchemas.UPDATE_OUTPUT : CartEditSchemas.DELETE_OUTPUT)
                .meta(Map.of("ui", Map.of("visibility", List.of("model", "app")), "openai/widgetAccessible", true))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false).destructiveHint(true)
                        .idempotentHint(true).openWorldHint(true).build()).build(), properties.writeScopes(),
                (identity, request) -> update ? service.update(identity, request.arguments())
                        : service.delete(identity, request.arguments()));
    }
}
