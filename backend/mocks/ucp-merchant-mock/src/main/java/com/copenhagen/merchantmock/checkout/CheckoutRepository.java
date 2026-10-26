package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.copenhagen.contract.internalapi.PaymentStatus;

/**
 * 체크아웃 저장 경계. 구현은 {@link JdbcCheckoutRepository}. 테스트는 인메모리 구현으로 돌린다.
 */
public interface CheckoutRepository {

    /**
     * 새 행을 넣는다. 같은 id 가 이미 있으면 false. 멱등 재시도가 이 경로로 들어온다(DESIGN.md 8.1절).
     */
    boolean insert(CheckoutSession session);

    Optional<CheckoutSession> findById(String id);

    /**
     * 조건부 UPDATE. {@code WHERE id = ? AND version = ?} 이 맞아야 1행이 바뀐다.
     * 0행이면 사이에 다른 요청이 바꿨거나 사라진 것이다. 성공하면 version 이 1 오른다.
     */
    boolean update(CheckoutSession session, long expectedVersion);

    /**
     * 취소 전이. status 가 취소 가능한 셋 중 하나일 때만 1행이 바뀐다(DESIGN.md 7.1절).
     *
     * <p>version 이 아니라 status 자체가 조건이다. 사이에 Complete 가 집어 갔거나 이미 취소됐으면 false 다.
     */
    boolean cancel(String id);

    /**
     * 만료 스윕. {@code ready_for_complete} 이면서 {@code expires_at} 이 지난 행을 {@code canceled} 로 바꾸고
     * 바뀐 건수를 돌려준다(DESIGN.md 7.1절). 인덱스 {@code ix_checkout_expiry} 를 탄다.
     *
     * @param limit 한 번에 닫는 최대 건수
     */
    int expireReady(OffsetDateTime now, int limit);

    /**
     * Complete 시작. {@code ready_for_complete} 이면서 {@code expires_at > now} 인 행만
     * {@code complete_in_progress} 로 바꾼다(DESIGN.md 7.1절). 만료 검사의 1차 방어선이 이 WHERE 다.
     *
     * <p>false 면 사이에 다른 요청이 집었거나 만료·취소됐다. 부르는 쪽이 다시 읽어 판단한다.
     */
    boolean beginComplete(String id, OffsetDateTime now);

    /**
     * Complete 성공. {@code complete_in_progress} 인 행만 {@code completed} 로 바꾸고 응답을 저장한다.
     * 이 응답이 같은 키 재요청에 그대로 재생된다(8.1절). 주문 INSERT 와 한 트랜잭션이어야 한다.
     */
    boolean finishComplete(String id, String completionResponseJson);

    /**
     * 결제 FAILED. {@code complete_in_progress} 인 행만 {@code ready_for_complete} 로 되돌린다(7.1절).
     * 재승인 뒤 새 Credential 로 다시 Complete 할 수 있게 하는 전이다(D-17).
     */
    boolean revertComplete(String id);

    /**
     * 청구 직전 표시. {@code payment_status} 가 없거나 {@code FAILED} 인 행만 {@code PROCESSING} 으로
     * 바꾼다(DESIGN.md 7.2절). FAILED 를 받는 것은 재승인 재청구 경로다(8.1절).
     *
     * <p>false 면 이 체크아웃에 진행 중이거나 끝난 결제가 있다. 부르는 쪽(PaymentService)은 그것을 재진입
     * 버그로 본다. {@code payment_attempt_count} 는 올리지 않는다. 그 컬럼은 재조회 횟수다(5.4절).
     */
    boolean beginPayment(String id, OffsetDateTime requestedAt, String credentialId,
            OffsetDateTime firstRecheckAt);

    /**
     * 결제 결과 반영. {@code payment_status = 'PROCESSING'} 인 행만 바꾼다(DESIGN.md 7.2절).
     *
     * @param payPaymentId Pay 가 준 paymentId. null 이면 있던 값을 지우지 않는다. UNKNOWN 이어도 있으면 저장한다
     */
    boolean finishPayment(String id, PaymentStatus status, String payPaymentId);

    /**
     * 기한이 된 {@code UNKNOWN} 을 집는다(183, DESIGN.md 8.8절). 집으면서 {@code payment_next_attempt_at} 을
     * {@code leaseUntil} 로 밀고 {@code payment_attempt_count} 를 올린다.
     *
     * <p>{@code payment_attempt_count >= maxAttempts} 는 집지 않는다. 한도를 넘긴 행은 상태를 바꾸지 않고
     * 스캔에서만 뺀다 — {@code UNKNOWN} 자체가 이미 사람이 확인해야 하는 상태라 옮길 곳이 없다(8.3절).
     */
    List<PaymentRecheck> claimUnknownDue(OffsetDateTime now, OffsetDateTime leaseUntil, int maxAttempts, int limit);

    /**
     * 재조회로 결과가 확정됐다. {@code UNKNOWN} 인 행만 바꾸고 {@code payment_next_attempt_at} 을 비운다.
     *
     * @param payPaymentId 조회로 처음 알게 된 경우가 있다. null 이면 기존 값을 지우지 않는다
     */
    boolean resolvePayment(String id, PaymentStatus status, String payPaymentId);

    /** 아직 {@code UNKNOWN} 이다. 다음에 볼 시각만 민다. */
    boolean scheduleNextRecheck(String id, OffsetDateTime nextAttemptAt);
}
