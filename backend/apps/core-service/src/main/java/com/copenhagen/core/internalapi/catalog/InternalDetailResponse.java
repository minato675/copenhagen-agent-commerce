package com.copenhagen.core.internalapi.catalog;

import java.util.List;

/**
 * {@code GET /internal/v1/merchants/{merchantId}/products/{merchantProductId}} 응답(설계서 §4.2).
 *
 * <p>{@code merchantId}·{@code merchantProductId}는 요청 값을 그대로 echo한다 — mcp-server가
 * 요청과 대조해 다르면 응답 전체를 버린다.
 *
 * <p>{@code stockQuantity}·{@code shipping.feeAmount}는 Merchant Catalog가 주지 않아 {@code null}이다.
 * 0으로 채우지 않는다(수집되지 않은 값과 0은 다르다).
 */
public record InternalDetailResponse(
        String merchantId,
        String merchantProductId,
        String name,
        String imageUrl,
        List<OptionGroup> optionGroups,
        List<Variant> variants,
        Shipping shipping,
        String checkedAt) {

    public record OptionGroup(String id, String name, List<InternalNamedId> values) {
    }

    public record Variant(
            String merchantVariantId,
            Long priceAmount,
            String currency,
            String availability,
            Long stockQuantity,
            boolean purchasable,
            int minQuantity,
            int maxQuantity,
            List<String> optionValueIds) {
    }

    public record Shipping(Long feeAmount, String status) {
    }
}
