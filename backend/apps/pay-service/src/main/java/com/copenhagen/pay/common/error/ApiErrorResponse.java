package com.copenhagen.pay.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiErrorResponse(
        String errorCode,
        String message,
        String currentState,
        String nextAction) {

    public static ApiErrorResponse from(PayErrorCode errorCode) {
        return new ApiErrorResponse(
                errorCode.code(),
                errorCode.defaultMessage(),
                null,
                errorCode.defaultNextAction());
    }
}
