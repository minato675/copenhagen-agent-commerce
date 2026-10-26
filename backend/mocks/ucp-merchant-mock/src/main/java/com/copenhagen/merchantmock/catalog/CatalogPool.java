package com.copenhagen.merchantmock.catalog;

import java.util.List;

/**
 * 카탈로그 풀 파일의 내용(DESIGN.md 5.7절 2026-09-21 개정, ADR 0011).
 *
 * <p>몰이 없고 id 가 없다. 상품 본문만 있고 몰별 편차는 {@link MallVariation} 이 만든다.
 * 순서가 곧 표본이다 — 앞은 시연 필수(기존 시드), 뒤는 sha256(externalId) 순이라 앞 N 건이 균등 표본이다.
 *
 * @param sha256     파일 바이트(압축된 그대로)의 sha256. 적재 건너뛰기의 기준이다
 * @param totalCount 파일에 든 상품 수. limit 으로 잘라 읽었어도 파일 전체 수를 말한다
 * @param products   읽은 상품. limit 이 있으면 앞 limit 건
 */
public record CatalogPool(String sha256, int totalCount, List<PoolProduct> products) {

    public record PoolProduct(
            String externalId,
            String title,
            String category,
            String brand,
            String imageUrl,
            long basePrice,
            String currency,
            String status,
            List<PoolVariant> variants) {
    }

    /** 풀의 옵션. {@code priceDelta} 는 원본 차액이고 {@code stock} 은 원본 재고(100/0)다. 몰 편차 전이다. */
    public record PoolVariant(
            String sku,
            String optionName,
            String optionValue,
            long priceDelta,
            int stock,
            String status) {
    }
}
