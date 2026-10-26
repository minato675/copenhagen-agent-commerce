package com.copenhagen.core.product;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.common.auth.CoreRequestContext;

@RestController
@RequestMapping("/api/core/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/search")
    public SearchEnvelope search(HttpServletRequest request, @Valid @RequestBody SearchRequestBody body) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return productService.search(principal, body.toQuery());
    }

    @GetMapping("/{productId}")
    public DetailEnvelope get(
            HttpServletRequest request,
            @PathVariable String productId,
            @RequestParam String merchantId,
            @RequestParam(required = false, defaultValue = "") String optionId) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return productService.getDetail(principal, productId, merchantId, optionId);
    }

    /** {@code CatalogSchemas.SEARCH_INPUT}과 동일한 기본값(page=1, pageSize=4, sort=RELEVANCE)을 쓴다. */
    public record SearchRequestBody(
            String query,
            String category,
            String brand,
            String merchantId,
            Long minPrice,
            Long maxPrice,
            @Min(1) Integer page,
            @Min(1) @Max(20) Integer pageSize,
            String sort) {

        SearchQuery toQuery() {
            return new SearchQuery(
                    orEmpty(query), orEmpty(category), orEmpty(brand), orEmpty(merchantId),
                    minPrice, maxPrice,
                    page == null ? 1 : page,
                    pageSize == null ? 4 : pageSize,
                    sort == null ? "RELEVANCE" : sort);
        }

        private static String orEmpty(String value) {
            return value == null ? "" : value;
        }
    }
}
