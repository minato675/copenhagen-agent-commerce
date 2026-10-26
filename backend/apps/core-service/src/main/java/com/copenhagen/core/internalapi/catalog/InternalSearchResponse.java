package com.copenhagen.core.internalapi.catalog;

import java.util.List;

/**
 * {@code GET /internal/v1/catalog/products} 응답(설계서 §4.1).
 *
 * <p>{@code nextCursor}는 다음 페이지가 없으면 {@code null}이지만 <b>키는 항상 있어야 한다</b> —
 * mcp-server {@code CoreCommerceService.search()}가 {@code containsKey("nextCursor")}로 검사한다.
 * 그래서 이 응답은 {@code NON_NULL} 직렬화를 쓰지 않는다.
 *
 * <p>{@code imageUrl}은 Merchant Catalog의 대표 이미지를 전달하며 누락된 경우 {@code null}이다.
 * {@code brand}는 제공되지 않아 {@code null}이고 {@code category}는 고정 플레이스홀더다.
 */
public record InternalSearchResponse(
        List<Product> products,
        Filters filters,
        List<String> failedMerchants,
        String nextCursor,
        String checkedAt) {

    public record Product(
            String comparisonGroupId,
            String name,
            String brand,
            String imageUrl,
            InternalNamedId category,
            List<Offer> offers) {
    }

    public record Offer(
            String merchantId,
            String merchantName,
            String merchantProductId,
            Long priceAmount,
            String currency,
            String availability) {
    }

    public record Filters(List<InternalNamedId> categories, List<InternalNamedId> merchants) {
    }
}
