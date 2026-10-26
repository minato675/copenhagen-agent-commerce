package com.copenhagen.core.cart;

import java.util.UUID;

/**
 * {@code selectedOptions}는 {@code cart_items.selected_options}(JSONB) 원문이다. 저장 형태는
 * {@code [{"optionValueId":"…","name":"…","label":"…"}]}이고, 첫 값이 {@code merchantVariantId}와 같다.
 */
public record CartItem(
        long id,
        UUID publicId,
        long cartId,
        long merchantId,
        String merchantProductId,
        String merchantVariantId,
        String productName,
        String productUrl,
        String imageUrl,
        String selectedOptions,
        int quantity,
        long unitPriceAmount,
        String currency) {
}
