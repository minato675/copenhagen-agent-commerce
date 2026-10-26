package com.copenhagen.mcp.cart;

import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CartProperties.class)
@ConditionalOnProperty(name = "mcp.cart.enabled", havingValue = "true")
public class CartToolConfiguration {

    @Bean
    CartService cartService(CoreCart core, JsonSchemaValidator validator, McpJsonMapper mapper) {
        return new CartService(core, validator, mapper);
    }

    @Bean
    CartViewService cartViewService(CoreCartView core, CartService boundary) {
        return new CartViewService(core, boundary);
    }

    @Bean
    McpToolDefinition getCart(CartViewService service, CartProperties properties) {
        return new McpToolDefinition(McpSchema.Tool.builder("get_cart", CartViewSchemas.INPUT)
                .title("내 장바구니와 견적 조회")
                .description("인증된 사용자의 현재 장바구니를 Core에서 조회합니다. 새 검색·새 위젯에서도 "
                        + "이 결과로 복원하세요. 금액은 각 통화의 최소 단위 정수이며 통화 간 합산하지 마세요. "
                        + "UNKNOWN/null 배송비·할인은 0이 아니며 INCOMPLETE/PARTIAL/UNAVAILABLE은 최종 견적이 "
                        + "아닙니다. ESTIMATED와 CONFIRMED를 구분하고 모든 변경 후 주문 검증·재승인이 필요합니다.")
                .outputSchema(CartViewSchemas.OUTPUT).meta(Map.of("ui", Map.of("visibility", List.of("model", "app")),
                        "openai/widgetAccessible", true))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build()).build(), properties.readScopes(),
                (identity, request) -> service.get(identity, request.arguments()));
    }

    @Bean
    McpToolDefinition addCartItem(CartService service, CartProperties properties) {
        return new McpToolDefinition(McpSchema.Tool.builder("add_cart_item", CartSchemas.ADD_INPUT)
                .title("장바구니에 상품 추가")
                .description("사용자가 선택한 판매처·상품·옵션·수량을 Core 장바구니에 추가합니다. "
                        + "get_cart로 받은 cartId/version을 사용하고 가격이나 사용자 ID를 입력하지 마세요. "
                        + "옵션 없는 상품만 optionId가 빈 문자열입니다. 같은 논리 요청의 재전송에는 "
                        + "idempotencyKey와 본문 전체를 그대로 유지합니다. 타임아웃/오류/UNKNOWN 이후 새 키로 "
                        + "재추가하지 말고 같은 요청으로 확인하세요. CONFLICT면 장바구니를 다시 조회하고 "
                        + "사용자의 새 의사를 확인하세요. APPLIED만 추가 완료이며 결제나 주문은 수행하지 않습니다.")
                .outputSchema(CartSchemas.ADD_OUTPUT).meta(Map.of("ui", Map.of("visibility", List.of("model", "app")),
                        "openai/widgetAccessible", true))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build()).build(), properties.writeScopes(),
                (identity, request) -> service.add(identity, request.arguments()));
    }
}
