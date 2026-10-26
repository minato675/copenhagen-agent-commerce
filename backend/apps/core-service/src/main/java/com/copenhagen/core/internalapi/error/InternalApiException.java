package com.copenhagen.core.internalapi.error;

import org.springframework.http.HttpStatus;

/** 내부 API 경로에서만 던진다. 앱 경로는 {@code CoreException}을 그대로 쓴다. */
public class InternalApiException extends RuntimeException {

    private final InternalErrorCode errorCode;
    private final HttpStatus httpStatus;

    public InternalApiException(InternalErrorCode errorCode) {
        this(errorCode, errorCode.httpStatus(), errorCode.defaultMessage());
    }

    public InternalApiException(InternalErrorCode errorCode, String message) {
        this(errorCode, errorCode.httpStatus(), message);
    }

    /** merchant 장애처럼 같은 코드라도 상태코드를 달리 써야 하는 경우에만 쓴다(502 vs 503). */
    public InternalApiException(InternalErrorCode errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public InternalErrorCode errorCode() {
        return errorCode;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
