package com.copenhagen.merchantmock.scenario;

/**
 * 시연용 고장 스위치. 열 종류다(DESIGN.md 9절).
 *
 * <p>172 단계에서 읽는 것은 PRICE_CHANGED, OUT_OF_STOCK, ADDRESS_NOT_SERVICEABLE, REQUIRES_ESCALATION 넷이다.
 * 나머지는 Complete 와 웹훅 단계가 읽는다.
 */
public enum Scenario {
    NORMAL,
    PRICE_CHANGED,
    OUT_OF_STOCK,
    ADDRESS_NOT_SERVICEABLE,
    REQUIRES_ESCALATION,
    INVALID_MERCHANT_SIGNATURE,
    COMPLETE_TIMEOUT_BEFORE_PROCESS,
    COMPLETE_TIMEOUT_AFTER_ORDER,
    WEBHOOK_DUPLICATE,
    WEBHOOK_DELAYED
}
