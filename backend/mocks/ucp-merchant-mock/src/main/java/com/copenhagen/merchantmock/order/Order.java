package com.copenhagen.merchantmock.order;

import java.time.OffsetDateTime;

/**
 * orders 한 행(DESIGN.md 5.4절).
 *
 * <p>{@code merchantId} 는 체크아웃에서 복사한 몰 스코프다(393). 조회는 경로의 몰과 대조하고 웹훅 페이로드에
 * {@code merchant_id} 로 실린다.
 *
 * <p>배송 다섯은 주문 시점 사본이다. 체크아웃의 {@code fulfillment_address} 를 조인해 읽지 않는다.
 * 같은 사람이라도 주문마다 수령인과 주소가 다를 수 있다.
 *
 * <p>{@code recipientPhone} 은 당분간 비어 있다. Core 가 보내는 것이 수령인·우편번호·주소·상세주소
 * 넷뿐이라 채울 값이 없다. 필드 추가는 11절 Core 합의 항목이다.
 *
 * <p>{@code shippingFeeAmount} 는 그 주문에서 받은 배송비다(427, V10). 도서산간 추가금이 포함된 값이고
 * 주문 시점에 고정한다. 배송비 규칙이 바뀌어도 과거 주문이 흔들리지 않아야 한다(ADR 0009).
 */
public record Order(
        String id,
        String merchantId,
        String checkoutSessionId,
        OrderStatus status,
        long totalAmount,
        long shippingFeeAmount,
        String currency,
        String rawOrderJson,
        String recipientName,
        String recipientPhone,
        String postalCode,
        String addressLine1,
        String addressLine2,
        long version,
        OffsetDateTime updatedAt) {
}
