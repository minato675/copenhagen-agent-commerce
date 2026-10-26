package com.copenhagen.merchantmock.checkout;

/**
 * UCP 공식 상태(DESIGN.md 7.1절). DB 는 대문자, API 는 소문자 snake.
 */
public enum CheckoutStatus {
    INCOMPLETE,
    REQUIRES_ESCALATION,
    READY_FOR_COMPLETE,
    COMPLETE_IN_PROGRESS,
    COMPLETED,
    CANCELED;

    public String apiValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 수정(PUT)과 취소(cancel)를 받을 수 있는 상태인지. 결제가 시작된 뒤에는 조건도 못 바꾸고 취소도 못 한다.
     *
     * <p>두 연산이 같은 집합을 쓴다(DESIGN.md 7.1절). 갈라 두면 한쪽만 고치는 사고가 난다.
     */
    public boolean modifiable() {
        return this == INCOMPLETE || this == REQUIRES_ESCALATION || this == READY_FOR_COMPLETE;
    }
}
