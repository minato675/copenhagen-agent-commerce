package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuthorizationSessionStore {

    /** 멱등키는 구매 요청 단위다. 판매처는 더 이상 축이 아니다. */
    Optional<AuthorizationSession> findByUserAndIdempotencyKey(
            String userId,
            String idempotencyKey);

    /** 구매 요청당 활성 승인은 1건이다. DB 유니크가 함께 보장한다. */
    Optional<AuthorizationSession> findActiveByPurchaseRequest(String purchaseRequestId);

    /** 세션과 판매처별 item 을 함께 적재한다. */
    boolean insert(AuthorizationSession session);

    List<AuthorizationSession> findByUserId(
            String userId,
            AuthorizationSessionStatus status);

    Optional<AuthorizationSession> findByIdAndUserId(
            String authorizationSessionId,
            String userId);

    int expireOverdueForUser(String userId, Instant now);

    int expireOverdueByIdAndUser(
            String authorizationSessionId,
            String userId,
            Instant now);

    default boolean approve(
            String authorizationSessionId,
            String userId,
            String authMethod,
            Instant approvedAt) {
        return false;
    }

    default boolean reject(
            String authorizationSessionId,
            String userId,
            Instant rejectedAt) {
        return false;
    }

    default boolean setRequiredAction(
            String authorizationSessionId,
            String userId,
            AuthorizationSessionStatus expectedStatus,
            String requiredAction,
            Instant now) {
        return false;
    }

    /**
     * 생체 credential 해제처럼 사용자가 더 이상 생체를 쓸 수 없게 된 경우, 진행 중인
     * 승인 세션을 PIN으로 돌린다. 이것이 없으면 사용자가 PIN을 입력해도 승인 확정이
     * {@code STATE_CONFLICT}로 거절된다.
     */
    default int fallbackPendingToPin(String userId, Instant now) {
        return 0;
    }

    /** 결제수단은 세션 단위다. 한 번 고르면 전 판매처에 적용된다. */
    default boolean selectPaymentMethod(
            String authorizationSessionId,
            String userId,
            String paymentMethodId,
            Instant now) {
        return false;
    }
}
