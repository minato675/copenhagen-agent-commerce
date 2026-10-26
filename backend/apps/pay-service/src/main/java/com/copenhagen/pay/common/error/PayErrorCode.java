package com.copenhagen.pay.common.error;

import org.springframework.http.HttpStatus;

public enum PayErrorCode {

    AUTHENTICATION_REQUIRED(
            HttpStatus.UNAUTHORIZED,
            "AUTHENTICATION_REQUIRED",
            "인증이 필요합니다.",
            "PROVIDE_ACCESS_TOKEN"),
    INVALID_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "INVALID_TOKEN",
            "유효하지 않은 인증 토큰입니다.",
            "REAUTHENTICATE"),
    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "이 요청을 수행할 권한이 없습니다.",
            "CHECK_PERMISSION"),
    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "요청 형식이 올바르지 않습니다.",
            "CHECK_REQUEST"),
    RESOURCE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RESOURCE_NOT_FOUND",
            "요청한 결제 리소스를 찾을 수 없습니다.",
            "CHECK_RESOURCE_ID"),
    STATE_CONFLICT(
            HttpStatus.CONFLICT,
            "STATE_CONFLICT",
            "현재 상태에서는 요청을 처리할 수 없습니다.",
            "REFRESH_CURRENT_STATE"),
    IDEMPOTENCY_KEY_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "IDEMPOTENCY_KEY_REQUIRED",
            "상태를 변경하는 요청에는 Idempotency-Key가 필요합니다.",
            "PROVIDE_IDEMPOTENCY_KEY"),
    IDEMPOTENCY_KEY_INVALID(
            HttpStatus.BAD_REQUEST,
            "IDEMPOTENCY_KEY_INVALID",
            "Idempotency-Key 형식이 올바르지 않습니다.",
            "PROVIDE_VALID_IDEMPOTENCY_KEY"),
    CHECKOUT_SIGNATURE_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "CHECKOUT_SIGNATURE_INVALID",
            "Merchant Checkout 서명을 검증할 수 없습니다.",
            "RESTART_CHECKOUT"),
    MERCHANT_KEY_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MERCHANT_KEY_UNAVAILABLE",
            "Merchant 공개키를 조회할 수 없습니다.",
            "RETRY_LATER"),
    IDEMPOTENCY_CONFLICT(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_CONFLICT",
            "동일한 멱등성 키로 다른 요청이 전달되었습니다.",
            "USE_NEW_IDEMPOTENCY_KEY"),
    PIN_ALREADY_REGISTERED(
            HttpStatus.CONFLICT,
            "PIN_ALREADY_REGISTERED",
            "결제 PIN이 이미 등록되어 있습니다.",
            "USE_PIN_CHANGE"),
    PIN_NOT_REGISTERED(
            HttpStatus.NOT_FOUND,
            "PIN_NOT_REGISTERED",
            "등록된 결제 PIN이 없습니다.",
            "REGISTER_PAYMENT_PIN"),
    PIN_POLICY_VIOLATION(
            HttpStatus.BAD_REQUEST,
            "PIN_POLICY_VIOLATION",
            "결제 PIN은 6자리 숫자여야 합니다.",
            "CHECK_PIN_FORMAT"),
    PIN_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "PIN_INVALID",
            "현재 결제 PIN이 올바르지 않습니다.",
            "CHECK_CURRENT_PIN"),
    PIN_MUST_DIFFER(
            HttpStatus.BAD_REQUEST,
            "PIN_MUST_DIFFER",
            "새 결제 PIN은 현재 PIN과 달라야 합니다.",
            "USE_DIFFERENT_PIN"),
    PIN_TEMPORARILY_LOCKED(
            HttpStatus.TOO_MANY_REQUESTS,
            "PIN_TEMPORARILY_LOCKED",
            "결제 PIN 입력이 일시적으로 잠겼습니다.",
            "TRY_AGAIN_LATER"),
    BIOMETRIC_CREDENTIAL_INVALID(
            HttpStatus.BAD_REQUEST,
            "BIOMETRIC_CREDENTIAL_INVALID",
            "생체인증 자격증명 형식이 올바르지 않습니다.",
            "CHECK_BIOMETRIC_CREDENTIAL"),
    BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED(
            HttpStatus.CONFLICT,
            "BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED",
            "해당 기기에 생체인증 자격증명이 이미 등록되어 있습니다.",
            "USE_EXISTING_CREDENTIAL"),
    BIOMETRIC_CREDENTIAL_NOT_REGISTERED(
            HttpStatus.NOT_FOUND,
            "BIOMETRIC_CREDENTIAL_NOT_REGISTERED",
            "등록된 생체인증 자격증명이 없습니다.",
            "ENTER_PAYMENT_PIN"),
    BIOMETRIC_DEVICE_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "BIOMETRIC_DEVICE_REQUIRED",
            "생체인증을 사용할 기기를 지정해야 합니다.",
            "PROVIDE_DEVICE_ID"),
    BIOMETRIC_CHALLENGE_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "BIOMETRIC_CHALLENGE_INVALID",
            "생체인증 Challenge 또는 서명이 올바르지 않습니다.",
            "ENTER_PAYMENT_PIN"),
    BIOMETRIC_CHALLENGE_EXPIRED(
            HttpStatus.GONE,
            "BIOMETRIC_CHALLENGE_EXPIRED",
            "생체인증 Challenge가 만료되었습니다.",
            "ENTER_PAYMENT_PIN"),
    BIOMETRIC_CHALLENGE_REPLAYED(
            HttpStatus.CONFLICT,
            "BIOMETRIC_CHALLENGE_REPLAYED",
            "이미 처리된 생체인증 Challenge입니다.",
            "ENTER_PAYMENT_PIN"),
    PAYMENT_METHOD_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "PAYMENT_METHOD_NOT_FOUND",
            "결제수단을 찾을 수 없습니다.",
            "CHECK_PAYMENT_METHOD_ID"),
    PAYMENT_METHOD_INACTIVE(
            HttpStatus.CONFLICT,
            "PAYMENT_METHOD_INACTIVE",
            "사용할 수 없는 결제수단입니다.",
            "SELECT_ACTIVE_PAYMENT_METHOD"),
    PAYMENT_METHOD_ALREADY_REGISTERED(
            HttpStatus.CONFLICT,
            "PAYMENT_METHOD_ALREADY_REGISTERED",
            "이미 등록된 결제수단입니다.",
            "USE_EXISTING_PAYMENT_METHOD"),
    PAYMENT_CREDENTIAL_EXPIRED(
            HttpStatus.GONE,
            "PAYMENT_CREDENTIAL_EXPIRED",
            "결제 자격증명이 만료되었습니다.",
            "RESTART_CHECKOUT"),
    PAYMENT_CREDENTIAL_REVOKED(
            HttpStatus.CONFLICT,
            "PAYMENT_CREDENTIAL_REVOKED",
            "결제 자격증명이 폐기되었습니다.",
            "RESTART_CHECKOUT"),
    CREDENTIAL_ALREADY_CONSUMED(
            HttpStatus.CONFLICT,
            "CREDENTIAL_ALREADY_CONSUMED",
            "이미 사용된 결제 자격증명입니다.",
            "RESTART_CHECKOUT"),
    CREDENTIAL_SCOPE_MISMATCH(
            HttpStatus.CONFLICT,
            "CREDENTIAL_SCOPE_MISMATCH",
            "결제 승인 범위와 요청 내용이 일치하지 않습니다.",
            "RESTART_CHECKOUT"),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "요청을 처리하는 중 오류가 발생했습니다.",
            "RETRY_LATER");

    private final HttpStatus httpStatus;
    private final String code;
    private final String defaultMessage;
    private final String defaultNextAction;

    PayErrorCode(
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
