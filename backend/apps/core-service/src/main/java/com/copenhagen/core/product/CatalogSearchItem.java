package com.copenhagen.core.product;

/**
 * 내부 API(순서 34)가 쓰는 Catalog 검색 한 건. 앱용 {@link CatalogProduct}와 달리 merchant 정보를
 * 항목마다 들고 있다 — 내부 계약은 같은 이름의 상품을 여러 merchant의 offer로 묶기 때문이다.
 */
public record CatalogSearchItem(
        String merchantId,
        String merchantName,
        String merchantProductId,
        String name,
        long priceAmount,
        String currency,
        String availability,
        String imageUrl) {
    public CatalogSearchItem(String merchantId, String merchantName, String merchantProductId, String name,
            long priceAmount, String currency, String availability) {
        this(merchantId, merchantName, merchantProductId, name, priceAmount, currency, availability, null);
    }
}
