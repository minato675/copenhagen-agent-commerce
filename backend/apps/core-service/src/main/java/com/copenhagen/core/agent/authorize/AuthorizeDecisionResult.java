package com.copenhagen.core.agent.authorize;

import java.util.List;
import java.util.UUID;

/** {@code POST /oauth/authorize/{requestId}/decision} 처리 결과. */
public sealed interface AuthorizeDecisionResult {

    /** requestId가 없거나 만료됐거나 이미 처리됨 — redirect_uri를 다시 신뢰하지 않고 직접 오류를 보여준다. */
    record NotFound(String message) implements AuthorizeDecisionResult {
    }

    record Denied(String redirectUri, String state) implements AuthorizeDecisionResult {
    }

    /** 로그인 실패 — 같은 동의 화면을 에러 메시지와 함께 다시 보여준다(리다이렉트하지 않음, 재시도 가능). */
    record LoginFailed(UUID requestId, String clientName, String logoUrl, List<String> scopes, String message)
            implements AuthorizeDecisionResult {
    }

    record Approved(String redirectUri, String code, String state) implements AuthorizeDecisionResult {
    }
}
