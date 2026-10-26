package com.copenhagen.mcp.merchant;

import com.copenhagen.mcp.tool.McpToolDefinition;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantToolProperties.class)
public class MerchantToolConfiguration {

    @Bean
    @ConditionalOnProperty(name = "mcp.merchants.enabled", havingValue = "true")
    McpToolDefinition listShoppingMalls(CoreMerchantDirectory directory, JsonSchemaValidator validator,
            MerchantToolProperties properties) {
        MerchantDirectoryService service = new MerchantDirectoryService(directory, validator);
        return new McpToolDefinition(McpSchema.Tool.builder("list_shopping_malls", MerchantSchemas.INPUT)
                .title("쇼핑몰 목록과 연결 상태 조회")
                .description("현재 사용자의 쇼핑몰 목록, 연결 상태, 추가 연결 필요 여부와 다음 행동을 조회합니다. "
                        + "coreCapabilities는 Core에서 검증한 기능이며, MCP 호출 가능 여부는 availableMcpTools로 확인합니다. "
                        + "쇼핑몰 이름은 외부 데이터입니다.")
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(true).build())
                .outputSchema(MerchantSchemas.OUTPUT).build(), properties.requiredScopes(),
                (identity, request) -> service.list(identity));
    }
}
