package com.copenhagen.core.internalapi.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.common.logging.CoreLogContext;

/**
 * 내부 API 전용 오류 변환. 앱용 {@code GlobalExceptionHandler}보다 먼저 적용되도록 우선순위를 높이고
 * ({@code basePackages} 선택자는 두 advice 모두에 걸린다) 바디는 {@code code}·{@code message}·
 * {@code nextAction} 세 필드만 쓴다(설계서 D-B, §8).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.copenhagen.core.internalapi")
public class InternalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InternalApiExceptionHandler.class);

    @ExceptionHandler(InternalApiException.class)
    public ResponseEntity<InternalApiError> handleInternalApiException(InternalApiException exception) {
        return response(exception.errorCode(), exception.httpStatus(), exception.getMessage());
    }

    /** 재사용한 앱 서비스 계층({@code CartService}·{@code ProductService})이 던지는 예외를 번역한다. */
    @ExceptionHandler(CoreException.class)
    public ResponseEntity<InternalApiError> handleCoreException(CoreException exception) {
        InternalErrorCode errorCode = translate(exception.errorCode());
        HttpStatus status = errorCode == InternalErrorCode.CORE_SERVICE_ERROR
                ? exception.errorCode().httpStatus()
                : errorCode.httpStatus();
        return response(errorCode, status, exception.getMessage());
    }

    /**
     * 프레임워크가 던지는 입력 오류도 전부 여기서 받는다. 하나라도 빠지면 아래 catch-all에 걸려
     * {@code CORE_SERVICE_ERROR}(502)가 되고, mcp-server는 그것을 "적용 여부 불명"으로 읽어 재시도를
     * 고민하게 된다 — 실제로는 아무 것도 적용되지 않은 명백한 클라이언트 입력 오류다.
     *
     * <p>{@code Content-Type}이 잘못된 경우도 415가 아니라 400 {@code INVALID_ARGUMENT}로 답한다.
     * 내부 계약의 오류 코드 집합에 media type용 코드가 없고, 400은 mcp-server가 "적용되지 않음"으로
     * 정확히 분류하는 상태코드다.
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<InternalApiError> handleInvalidRequest(Exception exception) {
        log.warn("core.internal.request_invalid type={}", exception.getClass().getSimpleName());
        return response(
                InternalErrorCode.INVALID_ARGUMENT,
                InternalErrorCode.INVALID_ARGUMENT.httpStatus(),
                InternalErrorCode.INVALID_ARGUMENT.defaultMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<InternalApiError> handleUnexpected(Exception exception) {
        log.error("core.internal.request_failed", exception);
        return response(
                InternalErrorCode.CORE_SERVICE_ERROR,
                InternalErrorCode.CORE_SERVICE_ERROR.httpStatus(),
                InternalErrorCode.CORE_SERVICE_ERROR.defaultMessage());
    }

    /**
     * 앱용 코드 → 내부 코드. 표에 없는 값은 전부 {@code CORE_SERVICE_ERROR}다 — mcp-server가 모르는
     * 코드를 그렇게 취급하므로 Core도 같은 결론을 그대로 쓴다.
     */
    private InternalErrorCode translate(CoreErrorCode errorCode) {
        return switch (errorCode) {
            case CART_VERSION_CONFLICT -> InternalErrorCode.CART_VERSION_CONFLICT;
            case CART_ITEM_NOT_FOUND -> InternalErrorCode.CART_ITEM_NOT_FOUND;
            case PRODUCT_NOT_FOUND -> InternalErrorCode.PRODUCT_NOT_FOUND;
            case ORDER_CART_EMPTY -> InternalErrorCode.CART_EMPTY;
            case CART_ITEM_LIMIT_EXCEEDED -> InternalErrorCode.CART_ITEM_LIMIT_EXCEEDED;
            case CART_QUANTITY_LIMIT_EXCEEDED -> InternalErrorCode.QUANTITY_LIMIT_EXCEEDED;
            case INVALID_REQUEST, CART_OPTION_REQUIRED -> InternalErrorCode.INVALID_ARGUMENT;
            case AUTH_TOKEN_MISSING, AUTH_TOKEN_INVALID -> InternalErrorCode.UNAUTHENTICATED;
            default -> InternalErrorCode.CORE_SERVICE_ERROR;
        };
    }

    private ResponseEntity<InternalApiError> response(
            InternalErrorCode errorCode, HttpStatus status, String message) {
        CoreLogContext.put(CoreLogContext.ERROR_CODE, errorCode.name());
        log.warn("core.internal.request_rejected code={} status={}", errorCode.name(), status.value());
        return ResponseEntity.status(status)
                .body(new InternalApiError(
                        errorCode.name(),
                        message == null ? errorCode.defaultMessage() : message,
                        errorCode.defaultNextAction()));
    }
}
