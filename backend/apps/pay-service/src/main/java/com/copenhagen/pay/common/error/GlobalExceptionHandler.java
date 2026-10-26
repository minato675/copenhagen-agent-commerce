package com.copenhagen.pay.common.error;

import com.copenhagen.pay.common.observability.PayLogContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.copenhagen.pay")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PayException.class)
    public ResponseEntity<ApiErrorResponse> handlePayException(PayException exception) {
        log.warn(
                "pay.request.rejected errorCode={} currentState={} nextAction={}",
                exception.errorCode().code(),
                exception.currentState(),
                exception.nextAction());
        return response(
                exception.errorCode(),
                exception.getMessage(),
                exception.currentState(),
                exception.nextAction());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception) {
        log.warn(
                "pay.request.validation_failed errorCode={} errorCount={}",
                PayErrorCode.INVALID_REQUEST.code(),
                exception.getErrorCount());
        return response(
                PayErrorCode.INVALID_REQUEST,
                "요청 값이 올바르지 않습니다.",
                null,
                "CHECK_REQUEST");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception) {
        log.warn("pay.request.body_unreadable errorCode={}", PayErrorCode.INVALID_REQUEST.code());
        return response(
                PayErrorCode.INVALID_REQUEST,
                "요청 본문을 읽을 수 없습니다.",
                null,
                "CHECK_REQUEST");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(
            MissingRequestHeaderException exception) {
        log.warn(
                "pay.request.missing_header errorCode={} header={}",
                PayErrorCode.INVALID_REQUEST.code(),
                exception.getHeaderName());
        return response(
                PayErrorCode.INVALID_REQUEST,
                "필수 요청 헤더가 없습니다.",
                null,
                "CHECK_REQUEST_HEADERS");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request) {
        log.error(
                "pay.request.unexpected_failed method={} path={} errorCode={} exceptionType={}",
                request.getMethod(),
                PayLogContext.safePath(request.getRequestURI()),
                PayErrorCode.INTERNAL_ERROR.code(),
                exception.getClass().getSimpleName());
        return response(
                PayErrorCode.INTERNAL_ERROR,
                PayErrorCode.INTERNAL_ERROR.defaultMessage(),
                null,
                PayErrorCode.INTERNAL_ERROR.defaultNextAction());
    }

    private ResponseEntity<ApiErrorResponse> response(
            PayErrorCode errorCode,
            String message,
            String currentState,
            String nextAction) {
        ApiErrorResponse response = new ApiErrorResponse(
                errorCode.code(),
                message,
                currentState,
                nextAction);
        return ResponseEntity.status(errorCode.httpStatus()).body(response);
    }
}
