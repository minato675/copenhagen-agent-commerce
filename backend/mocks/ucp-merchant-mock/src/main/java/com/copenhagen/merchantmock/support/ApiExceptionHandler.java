package com.copenhagen.merchantmock.support;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ApiException 을 팀 명세의 오류 응답으로 바꾼다.
 *
 * <p>Core 가 보낸 {@code X-Request-Id} 는 응답 본문에 싣지 않는다. 팀 명세 네 필드에 자리가 없다.
 * 요청을 추적하는 용도는 로그가 맡는다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handle(ApiException e, HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        LOG.info("요청 거절. errorCode={} status={} currentState={} nextAction={} requestId={}",
                e.errorCode(), e.status().value(), e.currentState(), e.nextAction(), requestId);
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(e.errorCode(), e.getMessage(), e.currentState(), e.nextAction()));
    }
}
