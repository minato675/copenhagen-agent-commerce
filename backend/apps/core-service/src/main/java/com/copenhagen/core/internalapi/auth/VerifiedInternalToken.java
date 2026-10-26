package com.copenhagen.core.internalapi.auth;

import java.util.Set;

/**
 * 서명·{@code iss}·{@code aud}·{@code exp} 검증을 통과한 Agent OAuth 토큰.
 *
 * @param subject      Service JWT는 {@code client_id}, Agent JWT는 사용자 {@code public_id}
 * @param scopes       {@code scope} 클레임(공백 구분)을 나눈 값
 * @param connectionId Agent JWT에만 있는 {@code connection_id}. Service JWT는 {@code null}
 */
public record VerifiedInternalToken(String subject, Set<String> scopes, String connectionId) {
}
