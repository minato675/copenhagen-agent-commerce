package com.copenhagen.core.checkout;

import java.time.Instant;

/**
 * {@code checkout_fulfillment_option_snapshots} 한 행.
 *
 * <p>merchant는 배송을 {@code {type, status, amount}}로만 답한다 — 배송수단 식별자·이름·방법이 없다.
 * 테이블의 NOT NULL 컬럼은 플레이스홀더로 채우고(순서 24·25와 같은 방침), 배송이 가능할 때만 한 행을
 * 만든다. merchant 주문서 하나가 곧 하나의 출고 그룹이라 {@code fulfillmentGroupKey}는 주문서 id다.
 */
public record CheckoutFulfillmentOption(
        String fulfillmentGroupKey,
        String shippingOptionId,
        String shippingOptionName,
        String deliveryMethod,
        long shippingFeeAmount,
        Instant estimatedDeliveryAt) {

    static final String DEFAULT_SHIPPING_OPTION_ID = "default";
    static final String DEFAULT_SHIPPING_OPTION_NAME = "기본 배송";
    static final String DELIVERY_METHOD_STANDARD = "STANDARD";

    public static CheckoutFulfillmentOption standard(String fulfillmentGroupKey, long shippingFeeAmount) {
        return new CheckoutFulfillmentOption(
                fulfillmentGroupKey,
                DEFAULT_SHIPPING_OPTION_ID,
                DEFAULT_SHIPPING_OPTION_NAME,
                DELIVERY_METHOD_STANDARD,
                shippingFeeAmount,
                null);
    }
}
