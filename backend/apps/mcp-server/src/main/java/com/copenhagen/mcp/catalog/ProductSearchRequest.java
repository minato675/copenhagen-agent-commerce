package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 요청마다 전체 조건을 받는다. 빠진 조건은 제거되며 다른 사용자/이전 검색의 상태를 재사용하지 않는다. */
public record ProductSearchRequest(String query, String category, String brand, String merchantId,
        Long minPrice, Long maxPrice, int page, int pageSize, String sort) {

    public static ProductSearchRequest parse(Map<String, Object> input, JsonSchemaValidator validator) {
        Map<String, Object> values = input == null ? Map.of() : input;
        if (!validator.validate(CatalogSchemas.SEARCH_INPUT, values).valid()) {
            throw new CoreClientException(ToolErrorCode.INVALID_ARGUMENT);
        }
        Long minimum = number(values, "minPrice");
        Long maximum = number(values, "maxPrice");
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new CoreClientException(ToolErrorCode.INVALID_ARGUMENT);
        }
        return new ProductSearchRequest(text(values, "query"), text(values, "category"),
                text(values, "brand"), text(values, "merchantId"), minimum, maximum,
                ((Number) values.getOrDefault("page", 1)).intValue(),
                ((Number) values.getOrDefault("pageSize", 4)).intValue(),
                (String) values.getOrDefault("sort", "RELEVANCE"));
    }

    public Map<String, Object> arguments() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("category", category);
        result.put("brand", brand);
        result.put("merchantId", merchantId);
        if (minPrice != null) {
            result.put("minPrice", minPrice);
        }
        if (maxPrice != null) {
            result.put("maxPrice", maxPrice);
        }
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("sort", sort);
        return Map.copyOf(result);
    }

    private static String text(Map<String, Object> values, String name) {
        return ((String) values.getOrDefault(name, "")).strip();
    }

    private static Long number(Map<String, Object> values, String name) {
        return values.containsKey(name) ? ((Number) values.get(name)).longValue() : null;
    }
}
