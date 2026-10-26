package com.copenhagen.merchantmock.order;

/**
 * order_items 한 행(DESIGN.md 5.4절). 주문된 상품 한 줄이다.
 *
 * <p>{@code productId} 는 products FK 다. 내가 파는 상품이라 관계가 실재하고 상품별 판매 집계가 이 FK 를
 * 탄다. {@code sku} 는 {@code variants_json} 안의 값이라 걸 대상이 없어 문자열이다.
 *
 * <p>{@code title} 과 {@code unitAmount} 는 주문 시점 사본이다. 상품이 바뀌어도 과거 주문은 그대로다.
 */
public record OrderItem(
        String id,
        String orderId,
        int lineNo,
        String productId,
        String sku,
        String title,
        long unitAmount,
        int quantity,
        long lineAmount) {

    /** {@code line_amount = unit_amount * quantity} 는 DB CHECK 도 건다(V5). */
    public static OrderItem of(String id, String orderId, int lineNo, String productId, String sku,
            String title, long unitAmount, int quantity) {
        return new OrderItem(id, orderId, lineNo, productId, sku, title,
                unitAmount, quantity, unitAmount * quantity);
    }
}
