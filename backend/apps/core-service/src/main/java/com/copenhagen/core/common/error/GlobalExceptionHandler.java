package com.copenhagen.core.common.error;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.copenhagen.core.common.logging.CoreLogContext;

@RestControllerAdvice(basePackages = "com.copenhagen.core")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CoreException.class)
    public ResponseEntity<ApiErrorResponse> handleCoreException(CoreException exception) {
        log.warn(
                "core.request.rejected errorCode={} currentState={} nextAction={}",
                exception.errorCode().code(),
                exception.currentState(),
                exception.nextAction());
        return response(
                exception.errorCode(),
                exception.getMessage(),
                exception.currentState(),
                exception.nextAction(),
                exception.retryAfterSeconds(),
                exception.issues());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception) {
        log.warn("core.request.validation_failed errorCount={}", exception.getErrorCount());
        return response(
                CoreErrorCode.INVALID_REQUEST,
                "요청 값이 올바르지 않습니다.",
                null,
                "CHECK_REQUEST");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception) {
        log.warn("core.request.body_unreadable");
        return response(
                CoreErrorCode.INVALID_REQUEST,
                "요청 본문을 읽을 수 없습니다.",
                null,
                "CHECK_REQUEST");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(
            MissingRequestHeaderException exception) {
        log.warn("core.request.missing_header header={}", exception.getHeaderName());
        return response(
                CoreErrorCode.INVALID_REQUEST,
                "필수 요청 헤더가 없습니다.",
                null,
                "CHECK_REQUEST_HEADERS");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception) {
        return response(
                CoreErrorCode.REQUEST_METHOD_NOT_ALLOWED,
                CoreErrorCode.REQUEST_METHOD_NOT_ALLOWED.defaultMessage(),
                null,
                CoreErrorCode.REQUEST_METHOD_NOT_ALLOWED.defaultNextAction());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception) {
        return response(
                CoreErrorCode.REQUEST_MEDIA_TYPE_UNSUPPORTED,
                CoreErrorCode.REQUEST_MEDIA_TYPE_UNSUPPORTED.defaultMessage(),
                null,
                CoreErrorCode.REQUEST_MEDIA_TYPE_UNSUPPORTED.defaultNextAction());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request) {
        log.error(
                "Unhandled Core API error: method={}, path={}",
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return response(
                CoreErrorCode.INTERNAL_ERROR,
                CoreErrorCode.INTERNAL_ERROR.defaultMessage(),
                null,
                CoreErrorCode.INTERNAL_ERROR.defaultNextAction());
    }

    private ResponseEntity<ApiErrorResponse> response(
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction) {
        return response(errorCode, message, currentState, nextAction, null, null);
    }

    private ResponseEntity<ApiErrorResponse> response(
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction,
            Long retryAfterSeconds,
            List<ApiErrorIssue> issues) {
        // 컨트롤러 단계 오류는 access 줄이 errorCode를 여기서만 알 수 있다(이슈 374).
        CoreLogContext.put(CoreLogContext.ERROR_CODE, errorCode.code());
        ApiErrorResponse response = new ApiErrorResponse(
                errorCode.code(),
                message,
                currentState,
                nextAction,
                retryAfterSeconds,
                issues);
        return ResponseEntity.status(errorCode.httpStatus()).body(response);
    }
}
