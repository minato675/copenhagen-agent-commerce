package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.auth.McpAuthError;
import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 인증된 Core 응답만 노출하며 외부 이름·설명을 실행 명령이나 HTML로 해석하지 않는다. */
public final class ProductCatalogService {

    private final CoreProductCatalog catalog;
    private final JsonSchemaValidator validator;
    private final McpJsonMapper mapper;

    public ProductCatalogService(CoreProductCatalog catalog, JsonSchemaValidator validator, McpJsonMapper mapper) {
        this.catalog = catalog;
        this.validator = validator;
        this.mapper = mapper;
    }

    public Map<String, Object> search(McpIdentity identity, Map<String, Object> arguments) {
        ProductSearchRequest query = ProductSearchRequest.parse(arguments, validator);
        Map<String, Object> data = checked(identity, catalog.search(identity, query), CatalogSchemas.CORE_SEARCH);
        ProductSearchRequest echoed;
        try {
            echoed = ProductSearchRequest.parse(map(data.get("query")), validator);
        } catch (CoreClientException exception) {
            throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE, exception);
        }
        if (!query.arguments().equals(echoed.arguments())) {
            throw invalid();
        }
        List<?> products = (List<?>) data.get("products");
        long total = ((Number) data.get("total")).longValue();
        String status = (String) data.get("status");
        List<?> failed = (List<?>) data.get("failedMerchants");
        if (products.size() > query.pageSize() || products.size() > total
                || !Boolean.valueOf((long) query.page() * query.pageSize() < total).equals(data.get("hasNext"))
                || (status.equals("PARTIAL") != !failed.isEmpty())
                || (status.equals("EMPTY") && !products.isEmpty())
                || (status.equals("OK") && products.isEmpty())
                || (status.equals("CLARIFY") && (!products.isEmpty() || total != 0
                        || !query.category().isEmpty() || ((List<?>) data.get("categories")).size() < 2))) {
            throw invalid();
        }
        Set<Object> ids = new HashSet<>();
        for (Object value : products) {
            Map<String, Object> product = map(value);
            if (!ids.add(product.get("productId")) || ((String) product.get("name")).isBlank()
                    || (!query.category().isEmpty() && !query.category().equals(product.get("category")))
                    || (!query.brand().isEmpty() && !query.brand().equals(product.get("brand")))) {
                throw invalid();
            }
            validateImage((String) product.get("imageUrl"));
            List<?> offers = (List<?>) product.get("offers");
            if (offers.isEmpty()) {
                throw invalid();
            }
            Set<Object> merchants = new HashSet<>();
            for (Object offerValue : offers) {
                Map<String, Object> offer = map(offerValue);
                Number price = (Number) offer.get("price");
                if (!merchants.add(offer.get("merchantId")) || failed.contains(offer.get("merchantId"))
                        || (!query.merchantId().isEmpty() && !query.merchantId().equals(offer.get("merchantId")))
                        || (query.minPrice() != null && (price == null || price.longValue() < query.minPrice()))
                        || (query.maxPrice() != null && (price == null || price.longValue() > query.maxPrice()))) {
                    throw invalid();
                }
            }
        }
        return data;
    }

    Map<String, Object> checked(McpIdentity identity, Map<String, Object> response, Map<String, Object> schema) {
        if (response == null) {
            throw invalid();
        }
        try {
            if (mapper.writeValueAsBytes(response).length > 131072 || !validator.validate(schema, response).valid()) {
                throw invalid();
            }
        } catch (IOException exception) {
            throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE, exception);
        }
        identity.requireOwner((String) response.get("publicUserRef"));
        if (!identity.connectionRef().equals(response.get("connectionRef"))) {
            throw new McpAuthException(McpAuthError.ACCESS_DENIED);
        }
        return map(response.get("data"));
    }

    static void validateImage(String value) {
        if (value.isEmpty()) {
            return;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw invalid();
            }
        } catch (IllegalArgumentException exception) {
            throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE, exception);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    static CoreClientException invalid() {
        return new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE);
    }
}
