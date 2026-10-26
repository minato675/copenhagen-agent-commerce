package com.copenhagen.core.internalapi.catalog;

/**
 * {@code search_products} 입력(mcp-server {@code CommerceSchemas.SEARCH}와 필드가 1:1 대응한다).
 * 전부 선택 값이고, 없으면 {@code null}이다.
 */
public record InternalSearchQuery(
        String query,
        String categoryId,
        String merchantId,
        String brand,
        Long minPrice,
        Long maxPrice,
        String sort,
        String cursor,
        Integer pageSize) {
}
