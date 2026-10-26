package com.copenhagen.merchantmock.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연관 상품 엔드포인트(DESIGN.md 6.8절, ADR 0014). UCP 표준이 아닌 벤더 확장 {@code com.copenhagen.catalog.related} 이고
 * Profile 에는 광고하지 않는다(Core 합의 전).
 *
 * <p>{@code merchant.catalog.related.enabled=false}(기본)이면 이 빈이 없어 경로도 없다 — 머지 전과 같다.
 */
@RestController
@ConditionalOnProperty(prefix = "merchant.catalog.related", name = "enabled", havingValue = "true")
@RequestMapping(path = "/api/ucp/v1/{merchantId}/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
public class RelatedController {

    private final RelatedService service;

    public RelatedController(RelatedService service) {
        this.service = service;
    }

    @PostMapping(path = "/related", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CatalogMessages.ItemsResponse related(
            @PathVariable String merchantId,
            @RequestBody(required = false) CatalogMessages.RelatedRequest request) {
        return service.related(merchantId, request);
    }
}
