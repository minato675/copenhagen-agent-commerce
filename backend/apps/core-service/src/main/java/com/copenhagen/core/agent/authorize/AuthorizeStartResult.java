package com.copenhagen.core.agent.authorize;

import java.util.List;
import java.util.UUID;

/** {@code GET /oauth/authorize} 처리 결과. redirect_uri를 신뢰할 수 있게 된 시점 이후에만 리다이렉트로 오류를 알린다. */
public sealed interface AuthorizeStartResult {

    /** client_id·redirect_uri 자체가 무효 — 아직 redirect_uri를 신뢰할 수 없어 직접 오류 화면을 보여준다. */
    record Rejected(String message) implements AuthorizeStartResult {
    }

    /** redirect_uri는 신뢰됐지만 scope가 허용 범위를 벗어남 — redirect_uri로 error를 돌려준다. */
    record ScopeRejected(String redirectUri, String state) implements AuthorizeStartResult {
    }

    /** 검증 통과, 로그인+동의 화면을 띄운다. */
    record Started(UUID requestId, String clientName, String logoUrl, List<String> scopes) implements
            AuthorizeStartResult {
    }
}
