package com.copenhagen.core.product;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code CatalogSchemas.SEARCH_INPUT}과 필드가 1:1 대응한다. 검색 응답의 {@code query}에도 그대로 echo된다.
 * {@code minPrice}/{@code maxPrice}가 없으면 키 자체를 생략한다 — mcp-server의
 * {@code ProductSearchRequest.arguments()}가 값이 없을 때 키를 넣지 않는 것과 맞추기 위함(요청·echo
 * 재파싱 시 {@code Map} 동등성 비교가 이 생략 여부에 의존한다).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchQuery(
        String query, String category, String brand, String merchantId,
        Long minPrice, Long maxPrice, int page, int pageSize, String sort) {
}
