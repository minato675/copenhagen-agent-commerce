package com.copenhagen.mcp.tool;

public enum ToolErrorCode {
    INVALID_ARGUMENT("Tool 입력 형식을 확인해 주세요."),
    ACTION_REFERENCE_UNAVAILABLE("선택 요청을 확인할 수 없어요. 상품이나 장바구니 카드를 다시 선택해주세요."),
    ACTION_REFERENCE_EXPIRED("선택 요청의 유효 시간이 지났어요. 최신 장바구니와 보존된 선택 내용을 확인해주세요."),
    ACTION_REFERENCE_AMBIGUOUS("동일한 선택 요청이 여러 개예요. 실행하지 않고 선택 내용을 다시 확인해주세요."),
    DIAGNOSTIC_SESSION_UNAVAILABLE("응답시간 측정 세션을 확인할 수 없습니다. 장바구니 만료가 아닙니다. 측정을 다시 시작해주세요."),
    CORE_SERVICE_ERROR("Core가 요청을 처리하지 못했습니다."),
    CORE_TIMEOUT("Core 응답을 확인하지 못했습니다. 처리 결과는 아직 확정되지 않았습니다."),
    CORE_UNAVAILABLE("Core에 연결할 수 없습니다. 처리 결과는 아직 확정되지 않았습니다."),
    CORE_INVALID_RESPONSE("Core 응답이 합의된 형식과 일치하지 않습니다."),
    INTERNAL_ERROR("Tool 처리 중 오류가 발생했습니다.");

    private final String message;

    ToolErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
