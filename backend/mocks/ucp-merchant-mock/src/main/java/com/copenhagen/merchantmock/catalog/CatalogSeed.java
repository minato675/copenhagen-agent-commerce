package com.copenhagen.merchantmock.catalog;

import java.util.List;

/**
 * 시연 카탈로그 시드 파일 한 장(DESIGN.md 5.7절).
 *
 * <p>{@code tools/build_catalog_pool.py} 가 만든 풀을 {@link MallVariation} 이 몰별로 전개한 것 을 그대로 담는다. 파일 하나가 몰 하나다. 파일 머리의
 * {@code mall} 이 몰 id 이고 로더가 그 값을 상품 하나하나의 {@code merchantId} 에 박는다(393).
 * 필드는 products 테이블 컬럼과 1:1 이고 variants 는 jsonb 로 들어갈 문자열로 들고 있는다.
 * 다시 파싱하지 않으므로 바이트가 흔들리지 않는다.
 */
public record CatalogSeed(String mall, String mallName, List<Entry> products) {

    public record Entry(
            String id,
            String merchantId,
            String externalId,
            String title,
            String category,
            String brand,
            String imageUrl,
            long basePrice,
            String currency,
            String status,
            String variantsJson) {
    }
}
