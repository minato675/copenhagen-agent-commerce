package com.copenhagen.core.common.error;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiErrorResponse(
        String errorCode,
        String message,
        String currentState,
        String nextAction,
        Long retryAfterSeconds,

        /**
         * 항목 단위 사유. 값이 없으면 <b>키 자체가 나타나지 않는다</b> — 클래스 기본값
         * {@code ALWAYS}를 이 프로퍼티에서만 덮으므로 기존 오류 응답의 JSON은 그대로다.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<ApiErrorIssue> issues) {

    public static ApiErrorResponse from(CoreErrorCode errorCode) {
        return new ApiErrorResponse(
                errorCode.code(),
                errorCode.defaultMessage(),
                null,
                errorCode.defaultNextAction(),
                null,
                null);
    }
}
