package com.copenhagen.core.agent.token;

/** {@code POST /oauth/token}의 grant_type별 파라미터를 한데 모은다(필드 대부분은 grant_type에 따라 선택적). */
public record TokenRequestParams(
        String grantType,
        String code,
        String redirectUri,
        String clientId,
        String codeVerifier,
        String clientSecret,
        String refreshToken,
        String clientAssertionType,
        String clientAssertion,
        String scope) {
}
