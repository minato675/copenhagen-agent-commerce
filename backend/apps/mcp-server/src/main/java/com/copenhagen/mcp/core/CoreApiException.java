package com.copenhagen.mcp.core;

import java.util.Map;

/** 외부 응답 원문과 인증값을 포함하지 않는 내부 API 오류. */
public final class CoreApiException extends RuntimeException {
    private final String code;
    private final String outcome;
    private final String requestId;

    public CoreApiException(String code, String outcome, String requestId) {
        super(code);
        this.code = code;
        this.outcome = outcome;
        this.requestId = requestId;
    }

    public Map<String, Object> body() {
        String action = switch (code) {
            case "CART_VERSION_CONFLICT", "CART_ITEM_NOT_FOUND" -> "REFETCH_CART";
            case "QUANTITY_LIMIT_EXCEEDED" -> "CHANGE_SELECTION";
            default -> outcome.equals("UNKNOWN") ? "RETRY_SAME_REQUEST" : "RETRY";
        };
        return Map.of("error", Map.of("code", code, "outcome", outcome, "requestId", requestId,
                "nextAction", action, "retryable", !code.equals("IDEMPOTENCY_KEY_CONFLICT"),
                "message", outcome.equals("UNKNOWN")
                        ? "처리 결과를 확인하지 못했습니다. 같은 요청으로 결과를 다시 확인해주세요."
                        : "요청을 처리하지 못했습니다. 최신 상태를 확인해주세요."));
    }
}
