package com.copenhagen.core.product;

import java.util.List;

/**
 * 내부 API 검색 결과. 호출에 실패한 merchant는 예외로 올리지 않고 {@code failedMerchants}에 남긴다 —
 * 한 판매처 장애로 전체 검색을 실패시키지 않는 기존 동작({@code ProductService.search})과 같다.
 */
public record CatalogSearchResult(List<CatalogSearchItem> items, List<String> failedMerchants) {
}
