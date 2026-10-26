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
@ConditionalOnProperty(name = {"mcp.integration.enabled", "mcp.ui.enabled"}, havingValue = "true")
public class ShoppingBatchConfiguration {
    @Bean
    ShoppingBatchService shoppingBatchService(CoreCommerceService commerce, JsonSchemaValidator validator,
            McpJsonMapper mapper) {
        return new ShoppingBatchService(commerce, validator, mapper, Clock.systemUTC());
    }

    @Bean
    McpToolDefinition searchProductsBatch(ShoppingBatchService service) {
        return tool("search_products_batch", "여러 재료 한 번에 검색",
                CommerceConfiguration.SEARCH_GUIDANCE + " 여러 재료는 개별 search_products 대신 이 도구로 "
                + "최대 8개 조건을 한 번에 조회하세요. groups에 상품이 있으면 searchReference로 "
                + "검색 결과 표시 요청일 때만 display_search_batch를 한 번 호출하세요. 담기 처리 중에는 출력하지 마세요. 빈 결과·실패만 있으면 텍스트로 안내합니다.",
                ShoppingBatchService.SEARCH, null,
                (identity, request) -> service.search(identity, request.arguments()));
    }

    @Bean
    McpToolDefinition displaySearchBatch(ShoppingBatchService service, CommerceUiConfiguration.CommerceViews views) {
        return tool("display_search_batch", "재료 검색 결과 카드",
                "search_products_batch의 searchReference로 이미 조회한 상품을 표시합니다. 재검색하지 않습니다. "
                + "참조는 5분간 유효합니다. 오류·빈 결과만 있으면 호출하지 마세요.", ShoppingBatchService.DISPLAY,
                views.metadata("display_search_batch", "catalog:read"),
                (identity, request) -> service.display(identity, request.arguments()));
    }

    @Bean
    McpToolDefinition getProductDetailsBatch(ShoppingBatchService service) {
        return tool("get_product_details_batch", "여러 상품 옵션 조회",
                CommerceConfiguration.OPTION_GUIDANCE
                + "사용자가 고른 여러 상품의 merchantId·merchantProductId를 최대 8개까지 한 번에 조회합니다. "
                + "반환된 옵션만 사용하고 선택이 모호하면 사용자에게 질문하세요. 장바구니 변경은 하지 않습니다. "
                + "상품별 실패는 results.error에 표시됩니다. 단일 상세 UI가 필요하면 display_product_detail을 사용합니다.",
                ShoppingBatchService.DETAILS, null,
                (identity, request) -> service.details(identity, request.arguments()));
    }

    private McpToolDefinition tool(String name, String title, String description, Map<String, Object> schema,
            Map<String, Object> metadata, java.util.function.BiFunction<com.copenhagen.mcp.auth.McpIdentity,
                    McpSchema.CallToolRequest, Map<String, Object>> handler) {
        return new McpToolDefinition(McpSchema.Tool.builder(name, schema).title(title).description(description)
                .outputSchema(CommerceSchemas.OUTPUT).meta(metadata == null
                        ? Map.of("securitySchemes",
                                List.of(Map.of("type", "oauth2", "scopes", List.of("catalog:read"))))
                        : metadata)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build()).build(), Set.of("catalog:read"), handler);
    }
}
