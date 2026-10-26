package com.copenhagen.core.internalapi.error;

/**
 * 내부 API 오류 바디. 앱용 {@code ApiErrorResponse}의 {@code errorCode}가 아니라 {@code code}다 —
 * mcp-server가 읽는 필드 이름이 그것이다(설계서 D-B).
 */
public record InternalApiError(String code, String message, String nextAction) {

    public static InternalApiError from(InternalErrorCode errorCode) {
        return new InternalApiError(
                errorCode.name(), errorCode.defaultMessage(), errorCode.defaultNextAction());
    }
}
