package com.copenhagen.core.payment;

import java.time.Instant;

/**
 * Merchant Complete Checkout 결과를 {@code orders}/{@code payment_transactions}에 최초로 쓴다(이슈 410).
 * 두 테이블 모두 V5/V6에 스키마만 있었고 이 인터페이스가 첫 구현이다. 순서33(이슈411, 주문 목록·상세
 * 조회)이 이 데이터를 읽기만 한다 — 쓰기는 이 경로가 유일하다.
 */
public interface PaymentCompletionRepository {

    /** {@code completed}. {@code orders}·{@code payment_transactions}를 한 트랜잭션으로 만든다. */
    void recordCompleted(
            long checkoutSnapshotId,
            long userId,
            String authorizationSessionId,
            String merchantOrderId,
            String payTransactionId,
            long amountMinor,
            String currency,
            String paymentLabel,
            Instant now);

    /** {@code ready_for_complete}(결제 실패로 되돌림) 또는 그 밖의 확정 실패. 주문은 만들지 않는다. */
    void recordFailed(
            long checkoutSnapshotId,
            String authorizationSessionId,
            String payTransactionId,
            long amountMinor,
            String currency,
            String failureCode,
            Instant now);
}
