package com.copenhagen.core.common.error;

import java.io.IOException;
import java.util.List;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.copenhagen.core.common.logging.CoreLogContext;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link GlobalExceptionHandler}는 DispatcherServlet 안쪽(컨트롤러) 예외만 처리한다. 서블릿 필터에서 발생하는
 * 인증 오류는 디스패치 전이라 그 핸들러를 타지 않으므로, 필터가 직접 오류 응답을 쓸 때 이 클래스를 쓴다.
 */
@Component
public class CoreApiErrorWriter {

    private final ObjectMapper objectMapper;

    public CoreApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, CoreException exception) throws IOException {
        write(
                response,
                exception.errorCode(),
                exception.getMessage(),
                exception.currentState(),
                exception.nextAction(),
                exception.retryAfterSeconds(),
                exception.issues());
    }

    public void write(HttpServletResponse response, CoreErrorCode errorCode) throws IOException {
        write(
                response,
                errorCode,
                errorCode.defaultMessage(),
                null,
                errorCode.defaultNextAction(),
                null,
                null);
    }

    private void write(
            HttpServletResponse response,
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction,
            Long retryAfterSeconds,
            List<ApiErrorIssue> issues) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        // 필터 단계 오류는 access 줄이 errorCode를 여기서만 알 수 있다(이슈 374).
        CoreLogContext.put(CoreLogContext.ERROR_CODE, errorCode.code());
        response.setStatus(errorCode.httpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                new ApiErrorResponse(
                        errorCode.code(), message, currentState, nextAction, retryAfterSeconds, issues));
    }
}
