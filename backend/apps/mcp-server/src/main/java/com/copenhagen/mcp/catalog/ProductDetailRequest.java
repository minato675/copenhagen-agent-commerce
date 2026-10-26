package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.Map;

public record ProductDetailRequest(String productId, String merchantId, String optionId) {

    public static ProductDetailRequest parse(Map<String, Object> input, JsonSchemaValidator validator) {
        if (input == null || !validator.validate(DetailSchemas.INPUT, input).valid()) {
            throw new CoreClientException(ToolErrorCode.INVALID_ARGUMENT);
        }
        return new ProductDetailRequest((String) input.get("productId"), (String) input.get("merchantId"),
                (String) input.getOrDefault("optionId", ""));
    }

    public Map<String, Object> arguments() {
        return optionId.isEmpty() ? Map.of("productId", productId, "merchantId", merchantId)
                : Map.of("productId", productId, "merchantId", merchantId, "optionId", optionId);
    }
}
