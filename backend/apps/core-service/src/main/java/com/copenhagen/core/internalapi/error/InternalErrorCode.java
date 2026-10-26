package com.copenhagen.core.internalapi.error;

import org.springframework.http.HttpStatus;

/**
 * 내부 API(순서 34) 전용 오류 코드. 앱용 {@link com.copenhagen.core.common.error.CoreErrorCode}와 값도
 * 바디 필드 이름도 다르다 — mcp-server {@code InternalCoreClient}는 {@code error.code} 또는 최상위
 * {@code code}만 읽고, 아래 코드 집합만 인식한다(그 밖의 값은 {@code CORE_SERVICE_ERROR}로 취급한다).
 */
public enum InternalErrorCode {

    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.", "CHECK_REQUEST"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다.", "CHECK_CREDENTIALS"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "허용되지 않은 접근입니다.", "CHECK_CONNECTION"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.", "CHECK_REQUEST"),
    VARIANT_NOT_FOUND(HttpStatus.NOT_FOUND, "선택할 수 없는 옵션입니다.", "CHECK_REQUEST"),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니 항목을 찾을 수 없습니다.", "REFETCH_CART"),
    CART_VERSION_CONFLICT(HttpStatus.CONFLICT, "장바구니가 이미 변경되었습니다.", "REFETCH_CART"),
    CART_ITEM_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "장바구니 항목 수를 초과했습니다.", "REMOVE_CART_ITEM"),
    CART_EMPTY(HttpStatus.CONFLICT, "장바구니가 비어 있습니다.", "ADD_CART_ITEM"),
    QUANTITY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "한 항목에 담을 수 있는 수량을 초과했습니다.", "REDUCE_QUANTITY"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 멱등키로 다른 요청이 이미 처리되었습니다.", "USE_NEW_KEY"),
    CORE_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "요청을 처리하지 못했습니다.", "RETRY_LATER");

    private final HttpStatus httpStatus;
    private final String defaultMessage;
    private final String defaultNextAction;

    InternalErrorCode(HttpStatus httpStatus, String defaultMessage, String defaultNextAction) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
        this.defaultNextAction = defaultNextAction;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public String defaultNextAction() {
        return defaultNextAction;
    }
}
