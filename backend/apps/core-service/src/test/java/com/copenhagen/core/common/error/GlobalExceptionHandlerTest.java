package com.copenhagen.core.common.error;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("CoreException을 던지면 해당 에러코드의 상태·본문으로 응답한다")
    void handleCoreException_duplicatedLoginId_returnsConflictWithErrorBody() {
        CoreException exception = new CoreException(CoreErrorCode.AUTH_LOGIN_ID_DUPLICATED);

        ResponseEntity<ApiErrorResponse> response = handler.handleCoreException(exception);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("AUTH_LOGIN_ID_DUPLICATED", response.getBody().errorCode());
        assertEquals("이미 사용 중인 아이디입니다.", response.getBody().message());
        assertNull(response.getBody().currentState());
        assertEquals("USE_DIFFERENT_LOGIN_ID", response.getBody().nextAction());
    }

    @Test
    @DisplayName("CoreException의 retryAfterSeconds가 응답 본문까지 전달된다")
    void handleCoreException_withRetryAfterSeconds_propagatesToBody() {
        CoreException exception = new CoreException(
                CoreErrorCode.AUTH_LOGIN_LOCKED, "잠김", null, "TRY_AGAIN_LATER", 900L, null);

        ResponseEntity<ApiErrorResponse> response = handler.handleCoreException(exception);

        assertEquals(900L, response.getBody().retryAfterSeconds());
    }

    @Test
    @DisplayName("retryAfterSeconds를 지정하지 않은 CoreException은 응답에서도 null이다")
    void handleCoreException_withoutRetryAfterSeconds_bodyFieldIsNull() {
        CoreException exception = new CoreException(CoreErrorCode.AUTH_LOGIN_ID_DUPLICATED);

        ResponseEntity<ApiErrorResponse> response = handler.handleCoreException(exception);

        assertNull(response.getBody().retryAfterSeconds());
    }

    @Test
    @DisplayName("currentState가 null이어도 응답 레코드 필드 자체는 유지된다")
    void apiErrorResponse_nullCurrentState_fieldStillPresent() {
        ApiErrorResponse response = ApiErrorResponse.from(CoreErrorCode.INVALID_REQUEST);

        assertNull(response.currentState());
        assertEquals("INVALID_REQUEST", response.errorCode());
    }

    @Test
    @DisplayName("MethodArgumentNotValidException은 400 INVALID_REQUEST로 매핑된다")
    void handleValidationException_returnsBadRequest() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);

        ResponseEntity<ApiErrorResponse> response = handler.handleValidationException(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_REQUEST", response.getBody().errorCode());
    }

    @Test
    @DisplayName("필수 헤더 누락은 400 INVALID_REQUEST로 매핑된다")
    void handleMissingHeader_returnsBadRequest() {
        MissingRequestHeaderException exception = mock(MissingRequestHeaderException.class);

        ResponseEntity<ApiErrorResponse> response = handler.handleMissingHeader(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("CHECK_REQUEST_HEADERS", response.getBody().nextAction());
    }

    @Test
    @DisplayName("매핑되지 않은 예외는 500 INTERNAL_ERROR로 응답하고 요청 정보를 남긴다")
    void handleUnexpectedException_returnsInternalError() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/core/v1/auth/signup");

        ResponseEntity<ApiErrorResponse> response =
                handler.handleUnexpectedException(new IllegalStateException("boom"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().errorCode());
        assertEquals("RETRY_LATER", response.getBody().nextAction());
    }
}
