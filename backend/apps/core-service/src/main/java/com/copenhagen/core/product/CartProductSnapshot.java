package com.copenhagen.core.product;

import java.util.List;

/**
 * 장바구니에 담는 시점에 Merchant Catalog에서 캡처한 상품 정보(순서 25).
 *
 * <p>여기 가격은 표시용 캐시다. 최종 거래 조건은 Checkout이 정한다(ADR-0004). {@code cart_items}에
 * {@code price_captured_at}이 있는 이유가 이것이다.
 *
 * <p>{@code productUrl}은 합성 플레이스홀더다 — {@code ucp-merchant-mock}의 Catalog 응답
 * ({@code CatalogMessages.ProductDetail})에 상품 url이 없는데 {@code cart_items.product_url}은
 * NOT NULL이다. {@code imageUrl}도 응답에 없어 {@code null}이다(컬럼은 nullable).
 */
public record CartProductSnapshot(
        String merchantProductId,
        String merchantVariantId,
        String productName,
        String productUrl,
        String imageUrl,
        long unitPriceAmount,
        String currency,
        List<SelectedOption> selectedOptions) {

    /** {@code optionValueId}는 merchant의 variant {@code sku}다({@code CatalogMessages.Option.from()}). */
    public record SelectedOption(String optionValueId, String name, String label) {
    }
}
