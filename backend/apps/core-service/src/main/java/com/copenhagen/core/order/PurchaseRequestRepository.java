package com.copenhagen.core.order;

import java.time.Instant;
import java.util.UUID;

public interface PurchaseRequestRepository {

    PurchaseRequest insertCreatingCheckouts(
            UUID publicId,
            long userId,
            long cartId,
            String requestChannel,
            String policyVersion,
            Instant now);

    /**
     * MCP 구매 인계(순서 34). 배송지 선택 전 단계라 {@code PENDING_ADDRESS}로 적재하고,
     * 인계 시점의 장바구니 버전과 링크 만료 시각을 함께 남긴다.
     */
    PurchaseRequest insertPendingAddress(
            UUID publicId,
            long userId,
            long cartId,
            long agentConnectionId,
            String policyVersion,
            int cartVersion,
            Instant expiresAt,
            Instant now);

    /** 검증이 반려된 구매 요청을 닫는다. 실패한 검증 결과는 재사용하지 않는다(ORDER-03). */
    void markFailed(long id, Instant now);
}
