package com.copenhagen.merchantmock.order;

import java.util.Locale;

/**
 * 주문 상태. 배송 생애주기다(DESIGN.md 7.3절).
 *
 * <p>팀 명세의 {@code PAYMENT_PENDING} 계열은 Core 의 통합 거래 상태이고 여기 값이 아니다. 결제가 끝나야
 * 주문이 생기므로 그 값들은 이 테이블에 나타날 수 없다. 근거와 남은 확인 항목은 11절에 있다.
 *
 * <p>철자는 {@code CANCELED} 다. UCP 와 Core 확정본 예시가 L 하나다.
 */
public enum OrderStatus {

    CREATED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELED;

    /**
     * Core 에 나가는 표기. 대문자 그대로다.
     *
     * <p>체크아웃이 소문자인 것과 다르다. 체크아웃 상태는 UCP 가 소문자로 정했고 주문 상태는 Core 확정본
     * 4.10 예시가 {@code SHIPPED} 로 대문자다.
     */
    public String apiValue() {
        return name().toUpperCase(Locale.ROOT);
    }
}
