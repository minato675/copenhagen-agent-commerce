package com.copenhagen.core.internalapi.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * mcp-server의 {@code search_products}·{@code get_product_detail}이 부르는 Core 엔드포인트(순서 34).
 *
 * <p>인증은 {@code InternalApiAuthenticationFilter}가 이미 끝냈다. 이 경로는 nginx가 외부에 노출하지
 * 않고({@code /internal/} 404) Docker 내부망에서만 닿는다.
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalCatalogController {

    private final InternalCatalogService catalogService;

    public InternalCatalogController(InternalCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/catalog/products")
    public InternalSearchResponse search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize) {
        return catalogService.search(new InternalSearchQuery(
                query, categoryId, merchantId, brand, minPrice, maxPrice, sort, cursor, pageSize));
    }

    @GetMapping("/merchants/{merchantId}/products/{merchantProductId}")
    public InternalDetailResponse detail(
            @PathVariable String merchantId, @PathVariable String merchantProductId) {
        return catalogService.detail(merchantId, merchantProductId);
    }
}
