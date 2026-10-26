package com.copenhagen.core.order;

import java.util.List;
import java.util.UUID;

/**
 * 주문 검증 성공 응답. {@code validationId}는 {@code purchase_requests.public_id}다.
 *
 * <p>명세는 {@code orderSnapshotId}를 최상위 단수 필드로 적지만, 주문서 스냅샷은 merchant 주문서마다
 * 하나씩 생기므로 {@code checkouts[]} 안에 둔다. 최상위에 두면 merchant가 여럿일 때 어느 주문서의
 * 스냅샷인지 응답만으로 알 수 없다. merchant가 하나인 배포에서는 {@code checkouts[0].orderSnapshotId}가
 * 명세의 값과 같다(명세 갱신 대상).
 */
public record OrderValidationResult(UUID validationId, List<MerchantCheckoutView> checkouts) {

    /** merchant가 확정한 금액을 그대로 옮긴다. Core는 다시 계산하지 않는다(ADR-0004). */
    public record MerchantCheckoutView(
            String merchantId,
            String merchantCheckoutId,
            UUID orderSnapshotId,
            String status,
            String expiresAt,
            Amount amount) {
    }

    public record Amount(long subtotal, long shippingFee, long total, String currency) {
    }
}
