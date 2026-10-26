package com.copenhagen.core.common.error;

import java.util.List;

public class CoreException extends RuntimeException {

    private final CoreErrorCode errorCode;
    private final String currentState;
    private final String nextAction;
    private final Long retryAfterSeconds;
    private final List<ApiErrorIssue> issues;

    public CoreException(CoreErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, errorCode.defaultNextAction(), null, null, null);
    }

    public CoreException(
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction) {
        this(errorCode, message, currentState, nextAction, null, null, null);
    }

    public CoreException(
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction,
            Throwable cause) {
        this(errorCode, message, currentState, nextAction, null, null, cause);
    }

    /** 항목 단위 사유를 함께 돌려주는 오류(순서 27의 주문 검증 반려). */
    public CoreException(
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction,
            List<ApiErrorIssue> issues) {
        this(errorCode, message, currentState, nextAction, null, issues, null);
    }

    public CoreException(
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction,
            Long retryAfterSeconds,
            Throwable cause) {
        this(errorCode, message, currentState, nextAction, retryAfterSeconds, null, cause);
    }

    public CoreException(
            CoreErrorCode errorCode,
            String message,
            String currentState,
            String nextAction,
            Long retryAfterSeconds,
            List<ApiErrorIssue> issues,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.currentState = currentState;
        this.nextAction = nextAction;
        this.retryAfterSeconds = retryAfterSeconds;
        this.issues = issues == null ? null : List.copyOf(issues);
    }

    public CoreErrorCode errorCode() {
        return errorCode;
    }

    public String currentState() {
        return currentState;
    }

    public String nextAction() {
        return nextAction;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 항목 단위 사유. 없으면 {@code null}이고, 그때 오류 응답에 {@code issues} 키가 나타나지 않는다. */
    public List<ApiErrorIssue> issues() {
        return issues;
    }
}
