package com.copenhagen.core.cart;

import java.util.List;
import java.util.UUID;

/**
 * {@code get_cart} 응답(명세: {@code cartId}, {@code version}, {@code items}, {@code summary}).
 *
 * <p>장바구니가 아직 없는 사용자는 {@code cartId = null}, {@code version = 0}이다. 첫 담기 요청은
 * {@code cartVersion = 0}으로 보내면 되고, 그때 ACTIVE 장바구니가 만들어지며 버전이 1이 된다.
 */
public record CartView(UUID cartId, int version, List<CartItemView> items, CartSummary summary) {

    public static CartView empty() {
        return new CartView(null, 0, List.of(), CartSummary.zero());
    }

    public record CartItemView(
            UUID cartItemId,
            String merchantId,
            String productId,
            List<String> optionValueIds,
            String productName,
            String productUrl,
            String imageUrl,
            int quantity,
            Price unitPrice,
            long lineTotal) {
    }

    public record Price(long amount, String currency) {
    }

    /**
     * {@code discountTotal}·{@code shippingFee}는 0 고정이다. 배송비는 merchant Catalog 응답에 없고
     * (순서 24의 {@code shipping.fee}도 플레이스홀더였다) 할인 개념은 아직 어디에도 없다.
     * 최종 금액은 Checkout이 확정한다(ADR-0004).
     */
    public record CartSummary(
            long itemsTotal, long discountTotal, long shippingFee, long estimatedTotal, String currency) {

        public static CartSummary zero() {
            return new CartSummary(0L, 0L, 0L, 0L, CartService.DEFAULT_CURRENCY);
        }
    }
}
