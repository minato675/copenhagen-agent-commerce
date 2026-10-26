package com.copenhagen.merchantmock.support;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드를 달고 나가는 예외. 코드 목록은 DESIGN.md 10절이 소유한다.
 *
 * <p>응답 형식은 팀 API 명세 v4 의 네 필드다({@link ErrorResponse}). {@code nextAction} 값은
 * 설계서 20장 표에서 가져온다. 표에 없는 코드는 성격이 같은 기존 값을 고르고 그 근거를 여기 남긴다.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final HttpStatus status;
    private final String currentState;
    private final String nextAction;

    public ApiException(String errorCode, HttpStatus status, String message,
                        String currentState, String nextAction) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.currentState = currentState;
        this.nextAction = nextAction;
    }

    /**
     * 없거나 단종된 상품. 사용자가 할 수 있는 일은 장바구니로 돌아가 다른 상품을 고르는 것이다.
     * 설계서 표의 {@code CHECKOUT_STOCK_INSUFFICIENT} 와 같은 상황이라 같은 값을 쓴다.
     */
    public static ApiException productNotFound(String productId) {
        return new ApiException("PRODUCT_NOT_FOUND", HttpStatus.NOT_FOUND,
                "상품을 찾을 수 없다: " + productId, null, "RETURN_TO_CART");
    }

    /**
     * 경로의 몰이 {@code merchant.malls[]} 에 없다(393, DESIGN.md 3.3절). 부르는 코드의 설정 문제라 {@code nextAction} 은
     * null 이다 — Core 의 {@code services.merchants[].id} 와 내 몰 id 가 어긋난 것이고 사용자가 할 일이 없다.
     */
    public static ApiException merchantNotFound(String merchantId) {
        return new ApiException("MERCHANT_NOT_FOUND", HttpStatus.NOT_FOUND,
                "몰을 찾을 수 없다: " + merchantId, null, null);
    }

    /** 없는 체크아웃. 이어갈 대상이 없으니 처음부터 다시 시작한다. 다른 몰의 체크아웃도 없는 것으로 답한다(393). */
    public static ApiException checkoutNotFound(String checkoutId) {
        return new ApiException("CHECKOUT_NOT_FOUND", HttpStatus.NOT_FOUND,
                "체크아웃을 찾을 수 없다: " + checkoutId, null, "RETURN_TO_CART");
    }

    /**
     * 없는 주문. 주문 조회는 장바구니 단계가 아니라 {@code RETURN_TO_CART} 를 쓰지 않는다.
     *
     * <p>Core 가 이 API 를 부르는 것은 웹훅이 유실됐다고 의심될 때다(Core 확정본 6.2). 없다는 답을 받은
     * Core 가 할 일은 거래 상태를 다시 확인하는 것이라 {@code CHECK_PURCHASE_STATUS} 를 쓴다.
     */
    public static ApiException orderNotFound(String orderId) {
        return new ApiException("ORDER_NOT_FOUND", HttpStatus.NOT_FOUND,
                "주문을 찾을 수 없다: " + orderId, null, "CHECK_PURCHASE_STATUS");
    }

    /**
     * 완료·취소된 체크아웃을 수정하거나 취소하려 했다. 이미 끝난 건이라 먼저 상태를 확인해야 한다.
     * 설계서 표의 {@code COMPLETION_UNKNOWN} 과 같은 안내다.
     */
    public static ApiException checkoutNotModifiable(String checkoutId, Enum<?> status) {
        String state = status.name().toLowerCase(java.util.Locale.ROOT);
        return new ApiException("CHECKOUT_NOT_MODIFIABLE", HttpStatus.CONFLICT,
                "이 상태에서는 체크아웃을 수정할 수 없다: " + checkoutId + " (" + status + ")",
                state, "CHECK_PURCHASE_STATUS");
    }

    /**
     * 아직 완료 조건을 못 채운 체크아웃에 Complete 가 왔다. 409 다(DESIGN.md 7.1절, 176).
     *
     * <p>{@code messages[]} 의 같은 코드와 같은 사실이라 이름을 같이 쓴다. {@code incomplete} 와
     * {@code requires_escalation} 둘이 여기로 오고 {@code currentState} 로 구별한다.
     */
    public static ApiException checkoutIncomplete(String checkoutId, Enum<?> status) {
        String state = status.name().toLowerCase(java.util.Locale.ROOT);
        return new ApiException("CHECKOUT_INCOMPLETE", HttpStatus.CONFLICT,
                "체크아웃이 아직 완료 조건을 채우지 못했다: " + checkoutId + " (" + status + ")",
                state, "UPDATE_CHECKOUT_INPUT");
    }

    /**
     * {@code expires_at} 이 지난 체크아웃에 Complete 가 왔다. 스윕 워커가 아직 닫지 않은 창이다(DESIGN.md 7.1절).
     * Pay 가 만료된 {@code checkoutJwt} 에 주는 것과 같은 {@code RESTART_CHECKOUT} 이다.
     */
    public static ApiException checkoutExpired(String checkoutId) {
        return new ApiException("CHECKOUT_EXPIRED", HttpStatus.CONFLICT,
                "체크아웃이 만료됐다: " + checkoutId, "ready_for_complete", "RESTART_CHECKOUT");
    }

    /**
     * 요청 형식 문제. 팀 명세 공통 규칙의 400 이다(DESIGN.md 10절). 시나리오 API 의 모르는 이름과 Complete 의
     * {@code credentialId} 누락이 여기다. {@code nextAction} 은 null 이다 — 형식 문제는 사용자가 아니라 부르는
     * 코드가 고칠 일이라 안내할 다음 행동이 없다.
     */
    public static ApiException invalidRequest(String message) {
        return new ApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, message, null, null);
    }

    /**
     * 연관 상품 색인을 기동 뒤 백그라운드에서 만드는 중이다(DESIGN.md 6.8.1절). 잠시 뒤 다시 부르면 된다. {@code nextAction} 은 null —
     * 다시 부를지는 사용자가 아니라 부르는 코드가 정한다.
     */
    public static ApiException relatedWarmingUp() {
        return new ApiException("RELATED_WARMING_UP", HttpStatus.SERVICE_UNAVAILABLE,
                "연관 상품을 준비하는 중이다. 잠시 뒤 다시 부른다", null, null);
    }

    /** 연관 상품 요청을 처리하다 예상하지 못한 오류가 났다. 이 요청만 실패하고 다른 API 는 그대로다(6.8.1절). */
    public static ApiException relatedUnavailable() {
        return new ApiException("RELATED_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                "연관 상품을 지금 줄 수 없다", null, null);
    }

    public String errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String currentState() {
        return currentState;
    }

    public String nextAction() {
        return nextAction;
    }
}
