package com.copenhagen.core.checkout;

/**
 * {@code checkout_snapshot_items} 한 행.
 *
 * <p>수량·단가는 merchant가 확정한 값이고, 상품 url과 선택 옵션 상세는 장바구니에서 온다 — merchant
 * Checkout 응답에는 그 둘이 없다.
 *
 * <p>{@code imageUrl}도 같은 이유로 장바구니에서 온다. merchant Catalog 응답에만 이미지가 있어
 * 담기 시점에 {@code cart_items.image_url}로 캡처해 두고, 여기로 옮겨 담아 주문내역까지 살린다.
 * 결제가 끝나면 장바구니 행은 지워지므로({@code PaidCartCleanupRepository}) 조인으로는 되살릴 수 없다.
 * 이미지가 없는 구형 제공자나 V22 이전 주문은 {@code null}이다.
 */
public record CheckoutSnapshotItem(
        String merchantProductId,
        String merchantVariantId,
        String productName,
        String productUrl,
        String imageUrl,
        String selectedOptions,
        int quantity,
        long unitPriceAmount,
        long discountAmount,
        long lineTotalAmount,
        String currency,
        java.util.UUID cartItemPublicId) {
    public CheckoutSnapshotItem(String merchantProductId, String merchantVariantId, String productName,
            String productUrl, String selectedOptions, int quantity, long unitPriceAmount,
            long discountAmount, long lineTotalAmount, String currency) {
        this(merchantProductId, merchantVariantId, productName, productUrl, null, selectedOptions, quantity,
                unitPriceAmount, discountAmount, lineTotalAmount, currency, null);
    }

    public CheckoutSnapshotItem(String merchantProductId, String merchantVariantId, String productName,
            String productUrl, String selectedOptions, int quantity, long unitPriceAmount,
            long discountAmount, long lineTotalAmount, String currency,
            java.util.UUID cartItemPublicId) {
        this(merchantProductId, merchantVariantId, productName, productUrl, null, selectedOptions, quantity,
                unitPriceAmount, discountAmount, lineTotalAmount, currency, cartItemPublicId);
    }
}
