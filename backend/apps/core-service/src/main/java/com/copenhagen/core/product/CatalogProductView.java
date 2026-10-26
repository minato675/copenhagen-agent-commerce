package com.copenhagen.core.product;

import java.util.List;

/**
 * 내부 API(순서 34)가 쓰는 상품 상세. Merchant Catalog 응답을 그대로 옮긴 형태다 —
 * 옵션 그룹에는 id가 없고 이름만 있으며, 옵션 값 id가 곧 merchant의 variant sku다.
 */
public record CatalogProductView(
        String merchantId,
        String merchantProductId,
        String name,
        long priceAmount,
        String currency,
        String availability,
        List<OptionGroup> optionGroups,
        String imageUrl) {

    public CatalogProductView(String merchantId, String merchantProductId, String name, long priceAmount,
            String currency, String availability, List<OptionGroup> optionGroups) {
        this(merchantId, merchantProductId, name, priceAmount, currency, availability, optionGroups, null);
    }

    public record OptionGroup(String name, List<OptionValue> values) {
    }

    public record OptionValue(String optionValueId, String label, Long priceAmount, Integer stockQuantity) {

        public OptionValue(String optionValueId, String label) {
            this(optionValueId, label, null, null);
        }
    }
}
