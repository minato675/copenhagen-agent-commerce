package com.copenhagen.core.internalapi.cart;

import java.util.List;

/**
 * {@code get_cart}와 장바구니 변경 3종의 공통 응답(설계서 §4.3). 변경도 같은 바디를 돌려준다 —
 * mcp-server는 응답에 {@code cartId}가 있으면 재조회 없이 그것을 현재 장바구니로 쓴다.
 *
 * <p>{@code discountAmount}·{@code shippingAmount}·{@code totalAmount}는 <b>{@code null}</b>이다.
 * 할인·배송비는 어디에서도 수집되지 않고 최종 금액은 Checkout이 정한다(ADR-0004). 앱용
 * {@code CartView}가 0으로 채우는 것과 의도적으로 다르다.
 */
public record InternalCartResponse(
        String cartId,
        int version,
        List<Item> items,
        List<MerchantSummary> merchantSummaries,
        Summary summary,
        List<String> failedMerchants) {

    public record Item(
            String cartItemId,
            String merchantId,
            String merchantProductId,
            String merchantVariantId,
            String name,
            String imageUrl,
            int quantity,
            Long unitPriceAmount,
            Long lineTotalAmount,
            String currency,
            List<SelectedOption> selectedOptions) {
    }

    public record SelectedOption(String name, String value) {
    }

    public record MerchantSummary(
            String merchantId,
            String merchantName,
            String currency,
            Long itemSubtotalAmount,
            Long discountAmount,
            Long shippingAmount,
            Long totalAmount,
            String status) {
    }

    public record Summary(
            String currency,
            Long itemSubtotalAmount,
            Long discountAmount,
            Long shippingAmount,
            Long totalAmount,
            String status) {
    }
}
