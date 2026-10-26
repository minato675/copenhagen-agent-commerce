package com.copenhagen.pay.payment.policy;

/**
 * 승인 세션 안의 판매처별 결제 결과. 세션은 사용자 인증까지만 책임지고 APPROVED 에서
 * 종료하며, 결제 성공/실패는 item 이 추적한다. 판매처 A 성공 · 판매처 B 실패를 세션
 * 하나의 상태로 표현할 수 없기 때문이다. 구매 전체 집계는 Core 가 담당한다.
 */
public enum AuthorizationSessionItemStatus {

    PENDING,
    COMPLETED,
    INVALIDATED,
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
