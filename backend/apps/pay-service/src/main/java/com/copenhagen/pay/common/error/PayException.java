package com.copenhagen.pay.common.error;

public class PayException extends RuntimeException {

    private final PayErrorCode errorCode;
    private final String currentState;
    private final String nextAction;

    public PayException(PayErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, errorCode.defaultNextAction(), null);
    }

    public PayException(
            PayErrorCode errorCode,
            String message,
            String currentState,
            String nextAction) {
        this(errorCode, message, currentState, nextAction, null);
    }

    public PayException(
            PayErrorCode errorCode,
            String message,
            String currentState,
            String nextAction,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.currentState = currentState;
        this.nextAction = nextAction;
    }

    public PayErrorCode errorCode() {
        return errorCode;
    }

    public String currentState() {
        return currentState;
    }

    public String nextAction() {
        return nextAction;
    }
}
