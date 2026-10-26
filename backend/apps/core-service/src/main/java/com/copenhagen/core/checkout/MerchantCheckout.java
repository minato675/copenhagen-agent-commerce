package com.copenhagen.core.checkout;

import java.time.Instant;

/**
 * {@code merchant_checkouts} 한 행.
 *
 * <p>{@code status}는 생성 직후 {@code CREATING}이다 — CHECK 제약의 값 중 "생성됐고 아직 완료로 가지
 * 않은" 상태를 가리키는 것이 이 값뿐이다. 완료 단계가 {@code COMPLETING}으로 전이시킨다.
 *
 * <p>주문서 본문(line item·금액·배송지)은 여기 저장하지 않는다. 그 자리는 주문서 스냅샷이다.
 */
public record MerchantCheckout(
        long id,
        long purchaseRequestId,
        long merchantId,
        String merchantCheckoutId,
        String checkoutDigest,
        String status,
        Instant checkoutCreatedAt,
        Instant checkoutExpiresAt) {

    public static final String STATUS_CREATING = "CREATING";
    public static final String STATUS_AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String STATUS_COMPLETING = "COMPLETING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_FAILED = "FAILED";

    /** 저장 전 행. {@code id}는 DB가 채운다. */
    public static MerchantCheckout creating(
            long purchaseRequestId,
            long merchantId,
            String merchantCheckoutId,
            String checkoutDigest,
            Instant checkoutCreatedAt,
            Instant checkoutExpiresAt) {
        return new MerchantCheckout(
                0L,
                purchaseRequestId,
                merchantId,
                merchantCheckoutId,
                checkoutDigest,
                STATUS_CREATING,
                checkoutCreatedAt,
                checkoutExpiresAt);
    }
}
