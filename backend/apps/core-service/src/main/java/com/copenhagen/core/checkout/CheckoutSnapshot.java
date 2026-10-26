package com.copenhagen.core.checkout;

import java.time.Instant;
import java.util.UUID;

import com.copenhagen.core.common.crypto.EncryptedPii;

/**
 * {@code checkout_snapshots} 한 행 — 사용자가 승인할 거래 조건을 그 시점 그대로 고정한 것.
 *
 * <p>{@code payload}는 merchant 주문서 응답 전체다. 금액 컬럼은 조회·표시를 위한 것이고 원본은 payload다.
 *
 * <p>배송지는 {@code addresses}의 암호문을 그대로 옮긴다 — 같은 키·같은 형식이라 복호화했다가 다시
 * 암호화할 이유가 없다. 연락처와 배송 요청사항은 UCP {@code Destination}에 자리가 없어 merchant에는
 * 전달되지 않지만, 주문 확인과 배송 처리에는 필요하므로 여기에는 남는다.
 */
public record CheckoutSnapshot(
        long id,
        UUID publicId,
        long merchantCheckoutId,
        String checkoutDigest,
        String payload,
        long subtotalAmount,
        long discountAmount,
        long taxAmount,
        long shippingFeeAmount,
        long totalAmount,
        String currency,
        EncryptedPii recipientNameEncrypted,
        EncryptedPii recipientPhoneEncrypted,
        EncryptedPii postalCodeEncrypted,
        EncryptedPii addressLine1Encrypted,
        EncryptedPii addressLine2Encrypted,
        EncryptedPii deliveryRequestEncrypted,
        Instant expiresAt) {
}
