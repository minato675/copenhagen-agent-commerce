package com.copenhagen.merchantmock.catalog;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog 세 엔드포인트. 전체 구매 19단계 중 4단계다.
 *
 * <p>세 개 다 POST 다. UCP REST Binding 이 그렇게 정해져 있다(DESIGN.md 3.3절).
 * 경로의 {@code merchantId} 가 어느 몰인지 말한다(393). 모르는 몰은 서비스가 404 로 거절한다.
 */
@RestController
@RequestMapping(path = "/api/ucp/v1/{merchantId}/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CatalogMessages.ItemsResponse search(
            @PathVariable String merchantId,
            @RequestBody(required = false) CatalogMessages.SearchRequest request) {
        return service.search(merchantId, request);
    }

    @PostMapping(path = "/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CatalogMessages.ItemsResponse lookup(
            @PathVariable String merchantId,
            @RequestBody(required = false) CatalogMessages.LookupRequest request) {
        return service.lookup(merchantId, request);
    }

    @PostMapping(path = "/product", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CatalogMessages.ProductDetail product(
            @PathVariable String merchantId,
            @RequestBody(required = false) CatalogMessages.ProductRequest request) {
        return service.product(merchantId, request);
    }
}
