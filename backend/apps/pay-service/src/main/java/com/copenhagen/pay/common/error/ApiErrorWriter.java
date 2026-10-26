package com.copenhagen.pay.common.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, PayException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                exception.errorCode().code(),
                exception.getMessage(),
                exception.currentState(),
                exception.nextAction());
        write(response, exception.errorCode().httpStatus().value(), errorResponse);
    }

    public void write(
            HttpServletResponse response,
            PayErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        write(response, errorCode.httpStatus().value(), ApiErrorResponse.from(errorCode));
    }

    private void write(
            HttpServletResponse response,
            int status,
            ApiErrorResponse errorResponse) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
