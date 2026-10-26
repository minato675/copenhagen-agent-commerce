package com.copenhagen.mcp.catalog;

import com.copenhagen.mcp.auth.McpIdentity;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.copenhagen.mcp.catalog.ProductCatalogService.invalid;
import static com.copenhagen.mcp.catalog.ProductCatalogService.map;

public final class ProductDetailService {

    private final CoreProductDetails core;
    private final ProductCatalogService boundary;
    private final JsonSchemaValidator validator;

    public ProductDetailService(CoreProductDetails core, ProductCatalogService boundary,
            JsonSchemaValidator validator) {
        this.core = core;
        this.boundary = boundary;
        this.validator = validator;
    }

    public Map<String, Object> get(McpIdentity identity, Map<String, Object> input) {
        ProductDetailRequest request = ProductDetailRequest.parse(input, validator);
        Map<String, Object> data = boundary.checked(identity, core.get(identity, request), DetailSchemas.CORE);
        if (!request.arguments().equals(data.get("request"))) {
            throw invalid();
        }
        if (data.get("status").equals("NOT_FOUND")) {
            if (data.get("product") != null) {
                throw invalid();
            }
            return data;
        }
        if (data.get("product") == null) {
            throw invalid();
        }
        Map<String, Object> product = map(data.get("product"));
        Map<String, Object> offer = map(product.get("offer"));
        if (!request.productId().equals(product.get("productId"))
                || !request.merchantId().equals(offer.get("merchantId")) || ((String) product.get("name")).isBlank()) {
            throw invalid();
        }
        ProductCatalogService.validateImage((String) product.get("imageUrl"));
        Set<Object> ids = new HashSet<>();
        Map<String, Object> selected = null;
        for (Object value : (List<?>) product.get("variants")) {
            Map<String, Object> variant = map(value);
            if (!ids.add(variant.get("optionId")) || ((String) variant.get("label")).isBlank()) {
                throw invalid();
            }
            if (request.optionId().equals(variant.get("optionId"))) {
                selected = variant;
            }
        }
        String status = (String) product.get("selectionStatus");
        if (selected != null) {
            if (!status.equals("VALID") || !request.optionId().equals(product.get("selectedOptionId"))
                    || !Objects.equals(selected.get("price"), offer.get("price"))
                    || !selected.get("availability").equals(offer.get("availability"))) {
                throw invalid();
            }
        } else if (product.get("selectedOptionId") != null || status.equals("VALID")
                || (!request.optionId().isEmpty() && !status.equals("INVALID"))
                || (request.optionId().isEmpty() && status.equals("INVALID"))) {
            throw invalid();
        }
        if (request.optionId().isEmpty() && (Boolean.TRUE.equals(product.get("optionsRequired"))
                != status.equals("REQUIRED"))) {
            throw invalid();
        }
        if ((status.equals("REQUIRED") || status.equals("INVALID")) && offer.get("price") != null) {
            throw invalid();
        }
        Map<String, Object> shipping = map(product.get("shipping"));
        if (shipping.get("status").equals("UNKNOWN") && shipping.get("fee") != null) {
            throw invalid();
        }
        return data;
    }
}
