package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Instant;
import java.util.List;

/**
 * 구매 요청 하나에 대한 사용자 승인 세션. 판매처가 몇 곳이든 세션은 1개이며 인증도 1회다.
 * 판매처별 정보는 {@link #items()} 에 있고 credential 도 item 당 1장씩 발급된다.
 *
 * <p>세션 상태는 APPROVED 에서 종료한다. 결제 성공/실패는 item 상태가 추적한다.
 */
public record AuthorizationSession(
        String id,
        String purchaseRequestId,
        String userId,
        long totalAmountMinor,
        String currency,
        String selectedPaymentMethodId,
        AuthorizationSessionStatus status,
        String idempotencyKey,
        String requestHash,
        String requiredAction,
        Instant expiresAt,
        Instant approvedAt,
        String authMethod,
        Instant createdAt,
        Instant updatedAt,
        int version,
        List<AuthorizationSessionItem> items) {

    public AuthorizationSession {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 목록 카드의 대표 판매처명. 판매처가 여러 곳이면 첫 번째를 쓴다. */
    public String primaryMerchantName() {
        return items.isEmpty() ? null : items.getFirst().merchantName();
    }

    public int merchantCount() {
        return items.size();
    }
}
