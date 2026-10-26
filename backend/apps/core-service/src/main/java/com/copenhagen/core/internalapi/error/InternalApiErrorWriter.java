package com.copenhagen.core.internalapi.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.copenhagen.core.common.logging.CoreLogContext;

import tools.jackson.databind.ObjectMapper;

/**
 * 필터 단계(디스패치 전) 오류를 내부 API 형식으로 직접 쓴다. 앱용 {@code CoreApiErrorWriter}와 역할은
 * 같지만 바디가 {@code errorCode}가 아니라 {@code code}다.
 */
@Component
public class InternalApiErrorWriter {

    private final ObjectMapper objectMapper;

    public InternalApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, InternalErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        // 필터 단계 오류는 access 줄(이슈 374)이 여기서만 코드를 알 수 있다.
        CoreLogContext.put(CoreLogContext.ERROR_CODE, errorCode.name());
        response.setStatus(errorCode.httpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), InternalApiError.from(errorCode));
    }
}
