package com.copenhagen.core.agent.token;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AGENT-03: Agent Token 발급. OAuth 표준에 맞춰 {@code error}/{@code error_description} 바디를 직접
 * 만든다({@code GlobalExceptionHandler}의 JSON 오류 포맷을 쓰지 않는다 — 설계서 §4.5).
 */
@RestController
public class OauthTokenController {

    private final OauthTokenService tokenService;

    public OauthTokenController(OauthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping(value = "/oauth/token", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<TokenResponseBody> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "code_verifier", required = false) String codeVerifier,
            @RequestParam(value = "client_secret", required = false) String clientSecret,
            @RequestParam(value = "refresh_token", required = false) String refreshToken,
            @RequestParam(value = "client_assertion_type", required = false) String clientAssertionType,
            @RequestParam(value = "client_assertion", required = false) String clientAssertion,
            @RequestParam(value = "scope", required = false) String scope) {
        TokenRequestParams params = new TokenRequestParams(
                grantType, code, redirectUri, clientId, codeVerifier, clientSecret, refreshToken,
                clientAssertionType, clientAssertion, scope);
        TokenResult result = tokenService.handle(params);
        return switch (result) {
            case TokenResult.Success success -> ResponseEntity.ok(TokenResponseBody.from(success));
            case TokenResult.Error error -> ResponseEntity.status(error.httpStatus())
                    .body(TokenResponseBody.error(error));
        };
    }

    /**
     * 필드명은 OAuth 표준(RFC 6749 §5.1/§5.2, snake_case)을 따른다. 공식 API 명세는 {@code expiresIn}
     * (camelCase)이라 적었지만, 실제 Agent(ChatGPT)도 표준 OAuth 클라이언트로 동작하고 이미 병합된
     * mcp-server {@code CoreServiceTokenProvider}도 {@code expires_in}을 그대로 파싱하므로, 명세 표기를
     * 문서 오기로 보고 표준 snake_case로 통일한다(설계서에서 이미 언급한 결정 필요 지점).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponseBody(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn,
            String scope,
            String error,
            @JsonProperty("error_description") String errorDescription) {

        static TokenResponseBody from(TokenResult.Success success) {
            return new TokenResponseBody(
                    success.accessToken(), success.refreshToken(), "Bearer", success.expiresIn(),
                    success.scope(), null, null);
        }

        static TokenResponseBody error(TokenResult.Error error) {
            return new TokenResponseBody(null, null, null, null, null, error.error(), error.description());
        }
    }
}
