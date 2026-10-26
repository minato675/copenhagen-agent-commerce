package com.copenhagen.core.common.error;

import org.springframework.http.HttpStatus;

public enum CoreErrorCode {
    PURCHASE_REQUEST_EXPIRED(HttpStatus.CONFLICT, "PURCHASE_REQUEST_EXPIRED",
            "구매 요청이 만료되었습니다.", "START_NEW_PURCHASE"),

    WEBHOOK_SIGNATURE_INVALID(
            HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", "Webhook 서명이 올바르지 않습니다.", "CHECK_SIGNATURE"),
    WEBHOOK_ID_CONFLICT(
            HttpStatus.CONFLICT, "WEBHOOK_ID_CONFLICT", "다른 본문에 같은 Webhook ID가 사용되었습니다.", "CHECK_REQUEST"),
    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "요청 값이 올바르지 않습니다.",
            "CHECK_REQUEST"),
    AUTH_LOGIN_ID_DUPLICATED(
            HttpStatus.CONFLICT,
            "AUTH_LOGIN_ID_DUPLICATED",
            "이미 사용 중인 아이디입니다.",
            "USE_DIFFERENT_LOGIN_ID"),
    AUTH_EMAIL_DUPLICATED(
            HttpStatus.CONFLICT,
            "AUTH_EMAIL_DUPLICATED",
            "이미 사용 중인 이메일입니다.",
            "USE_DIFFERENT_EMAIL"),
    AUTH_INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "AUTH_INVALID_CREDENTIALS",
            "아이디 또는 비밀번호가 올바르지 않습니다.",
            "CHECK_CREDENTIALS"),
    AUTH_ACCOUNT_UNAVAILABLE(
            HttpStatus.FORBIDDEN,
            "AUTH_ACCOUNT_UNAVAILABLE",
            "이용할 수 없는 계정입니다.",
            "CONTACT_SUPPORT"),
    AUTH_LOGIN_LOCKED(
            HttpStatus.LOCKED,
            "AUTH_LOGIN_LOCKED",
            "로그인이 일시적으로 제한되었습니다.",
            "TRY_AGAIN_LATER"),
    AUTH_TOKEN_MISSING(
            HttpStatus.UNAUTHORIZED,
            "AUTH_TOKEN_MISSING",
            "인증 토큰이 필요합니다.",
            "LOGIN_REQUIRED"),
    AUTH_TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED,
            "AUTH_TOKEN_INVALID",
            "유효하지 않은 인증 토큰입니다.",
            "LOGIN_REQUIRED"),
    AUTH_REFRESH_TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED,
            "AUTH_REFRESH_TOKEN_INVALID",
            "유효하지 않은 Refresh Token입니다.",
            "LOGIN_REQUIRED"),
    AUTH_REFRESH_TOKEN_REUSED(
            HttpStatus.UNAUTHORIZED,
            "AUTH_REFRESH_TOKEN_REUSED",
            "이미 사용된 Refresh Token입니다. 보안을 위해 모든 기기에서 로그아웃되었습니다.",
            "LOGIN_REQUIRED"),
    ADDRESS_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "ADDRESS_NOT_FOUND",
            "배송지를 찾을 수 없습니다.",
            "CHECK_REQUEST"),
    ADDRESS_LIMIT_EXCEEDED(
            HttpStatus.CONFLICT,
            "ADDRESS_LIMIT_EXCEEDED",
            "등록 가능한 배송지 개수를 초과했습니다.",
            "DELETE_EXISTING_ADDRESS"),
    PRODUCT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "PRODUCT_NOT_FOUND",
            "상품을 찾을 수 없습니다.",
            "CHECK_REQUEST"),
    MERCHANT_CATALOG_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MERCHANT_CATALOG_UNAVAILABLE",
            "판매자 서비스에 일시적으로 연결할 수 없습니다.",
            "RETRY_LATER"),
    CART_VERSION_CONFLICT(
            HttpStatus.CONFLICT,
            "CART_VERSION_CONFLICT",
            "장바구니가 이미 변경되었습니다. 다시 조회한 뒤 시도해 주세요.",
            "REFETCH_CART"),
    CART_ITEM_LIMIT_EXCEEDED(
            HttpStatus.CONFLICT,
            "CART_ITEM_LIMIT_EXCEEDED",
            "장바구니에는 최대 100개 항목을 담을 수 있습니다.",
            "REMOVE_CART_ITEM"),
    CART_ITEM_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "CART_ITEM_NOT_FOUND",
            "장바구니 항목을 찾을 수 없습니다.",
            "CHECK_REQUEST"),
    /** 줄당 수량 상한은 내부 API(순서 34)에만 있다. 앱 경로는 상한을 두지 않는다. */
    CART_QUANTITY_LIMIT_EXCEEDED(
            HttpStatus.CONFLICT,
            "CART_QUANTITY_LIMIT_EXCEEDED",
            "한 항목에 담을 수 있는 수량을 초과했습니다.",
            "REDUCE_QUANTITY"),
    CART_OPTION_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "CART_OPTION_REQUIRED",
            "옵션을 선택해야 장바구니에 담을 수 있습니다.",
            "SELECT_OPTION"),
    ORDER_CART_EMPTY(
            HttpStatus.CONFLICT,
            "ORDER_CART_EMPTY",
            "장바구니가 비어 있어 주문을 검증할 수 없습니다.",
            "ADD_CART_ITEM"),
    ORDER_VALIDATION_FAILED(
            HttpStatus.CONFLICT,
            "ORDER_VALIDATION_FAILED",
            "주문을 검증하지 못했습니다.",
            "RETURN_TO_CART"),
    ORDER_PRICE_CHANGED(
            HttpStatus.CONFLICT,
            "ORDER_PRICE_CHANGED",
            "상품 가격이 변경되었습니다.",
            "RETURN_TO_CART"),
    ORDER_STOCK_INSUFFICIENT(
            HttpStatus.CONFLICT,
            "ORDER_STOCK_INSUFFICIENT",
            "재고가 부족한 상품이 있습니다.",
            "RETURN_TO_CART"),
    ORDER_ADDRESS_NOT_SERVICEABLE(
            HttpStatus.CONFLICT,
            "ORDER_ADDRESS_NOT_SERVICEABLE",
            "선택한 배송지로는 주문할 수 없습니다.",
            "CHANGE_ADDRESS"),
    ORDER_MERCHANT_HANDOFF_REQUIRED(
            HttpStatus.CONFLICT,
            "ORDER_MERCHANT_HANDOFF_REQUIRED",
            "판매자에게 직접 문의가 필요한 주문입니다.",
            "CONTACT_MERCHANT"),
    ORDER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "ORDER_NOT_FOUND",
            "주문을 찾을 수 없습니다.",
            "CHECK_REQUEST"),
    MERCHANT_CHECKOUT_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MERCHANT_CHECKOUT_UNAVAILABLE",
            "판매자 주문 서비스에 일시적으로 연결할 수 없습니다.",
            "RETRY_LATER"),
    PAYMENT_APPROVAL_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "PAYMENT_APPROVAL_UNAVAILABLE",
            "결제 승인 서비스에 일시적으로 연결할 수 없습니다.",
            "RETRY_LATER"),
    /** 승인 세션 스키마 이관 드레인 구간(이슈 S15P21A207-486). 장애가 아니라 계획된 중단이다. */
    PURCHASE_SUSPENDED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "PURCHASE_SUSPENDED",
            "결제 점검 중입니다. 잠시 후 다시 시도해 주세요.",
            "RETRY_LATER"),
    MERCHANT_PROFILE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MERCHANT_PROFILE_UNAVAILABLE",
            "판매자 프로필을 조회할 수 없습니다.",
            "RETRY_LATER"),
    MERCHANT_PROFILE_INVALID(
            HttpStatus.BAD_GATEWAY,
            "MERCHANT_PROFILE_INVALID",
            "판매자 프로필 형식이 올바르지 않습니다.",
            "CONTACT_MERCHANT"),
    MERCHANT_UCP_VERSION_UNSUPPORTED(
            HttpStatus.BAD_GATEWAY,
            "MERCHANT_UCP_VERSION_UNSUPPORTED",
            "지원하지 않는 판매자 UCP 버전입니다.",
            "CONTACT_MERCHANT"),
    MERCHANT_CAPABILITY_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MERCHANT_CAPABILITY_UNAVAILABLE",
            "판매자가 필요한 기능을 지원하지 않습니다.",
            "CHOOSE_OTHER_MERCHANT"),
    AGENT_CONNECTION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "AGENT_CONNECTION_NOT_FOUND",
            "연동을 찾을 수 없습니다.",
            "CHECK_REQUEST"),
    OAUTH_CLIENT_UNKNOWN(
            HttpStatus.BAD_REQUEST,
            "OAUTH_CLIENT_UNKNOWN",
            "알 수 없는 클라이언트입니다.",
            "CHECK_CLIENT_ID"),
    OAUTH_REDIRECT_URI_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "OAUTH_REDIRECT_URI_MISMATCH",
            "등록되지 않은 리다이렉트 URI입니다.",
            "CHECK_REDIRECT_URI"),
    OAUTH_INVALID_SCOPE(
            HttpStatus.BAD_REQUEST,
            "OAUTH_INVALID_SCOPE",
            "허용되지 않은 scope가 포함되어 있습니다.",
            "CHECK_SCOPE"),
    OAUTH_INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "OAUTH_INVALID_REQUEST",
            "요청 값이 올바르지 않습니다.",
            "CHECK_REQUEST"),
    OAUTH_INVALID_GRANT(
            HttpStatus.BAD_REQUEST,
            "OAUTH_INVALID_GRANT",
            "유효하지 않거나 만료·소비된 코드입니다.",
            "RESTART_AUTHORIZATION"),
    OAUTH_INVALID_CLIENT(
            HttpStatus.UNAUTHORIZED,
            "OAUTH_INVALID_CLIENT",
            "클라이언트 인증에 실패했습니다.",
            "CHECK_CLIENT_CREDENTIALS"),
    OAUTH_UNSUPPORTED_GRANT_TYPE(
            HttpStatus.BAD_REQUEST,
            "OAUTH_UNSUPPORTED_GRANT_TYPE",
            "지원하지 않는 grant_type입니다.",
            "CHECK_GRANT_TYPE"),
    REQUEST_METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED,
            "REQUEST_METHOD_NOT_ALLOWED",
            "허용되지 않은 요청 메서드입니다.",
            "CHECK_REQUEST_METHOD"),
    REQUEST_MEDIA_TYPE_UNSUPPORTED(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "REQUEST_MEDIA_TYPE_UNSUPPORTED",
            "지원하지 않는 Content-Type입니다.",
            "CHECK_CONTENT_TYPE"),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "요청을 처리하는 중 오류가 발생했습니다.",
            "RETRY_LATER");

    private final HttpStatus httpStatus;
    private final String code;
    private final String defaultMessage;
    private final String defaultNextAction;

    CoreErrorCode(
            HttpStatus httpStatus,
            String code,
            String defaultMessage,
            String defaultNextAction) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.defaultNextAction = defaultNextAction;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public String defaultNextAction() {
        return defaultNextAction;
    }
}
