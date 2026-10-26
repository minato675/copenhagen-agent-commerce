package com.copenhagen.core.agent.authorize;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.agent.discovery.OauthDiscoveryController;
import com.copenhagen.core.agent.jwt.AgentOauthProperties;
import com.copenhagen.core.common.auth.CoreAuthenticationFilter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /oauth/authorize}는 공개 엔드포인트라 필터가 필요 없지만, {@code @WebMvcTest}가 컴포넌트
 * 스캔으로 {@code CoreAuthenticationFilter}까지 끌어와 그 생성자 의존성(JWKS 등)을 요구하므로 제외한다. */
@WebMvcTest(
        controllers = {OauthAuthorizeController.class, OauthDiscoveryController.class},
        properties = {
            "agent.oauth.issuer=https://core.example.com/auth/",
            "agent.oauth.mcp-resource-audience=https://core.example.com/mcp"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(AgentOauthProperties.class)
class OauthAuthorizeControllerTest {

    private static final String ISS_PARAMETER = "&iss=https%3A%2F%2Fcore.example.com%2Fauth%2F";
    private static final String STABLE_CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthorizeConsentService consentService;

    @ParameterizedTest
    @ValueSource(strings = {"consent.css", "copenhagen-logo.png", "IBMPlexSansKR-Regular.woff2",
        "IBMPlexSansKR-SemiBold.woff2"})
    @DisplayName("로그인 화면 정적 리소스를 기존 OAuth 하위 경로에서 제공한다")
    void consentAssets_areServed(String filename) throws Exception {
        try (InputStream asset = getClass().getResourceAsStream("/static/oauth/authorize/assets/v1/" + filename)) {
            mockMvc.perform(get("/oauth/authorize/assets/v1/" + filename))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(asset.readAllBytes()));
        }
    }

    @Test
    @DisplayName("연결 거부는 빈 로그인 입력으로도 기존 콜백에 전달된다")
    void decide_denied_withoutCredentials() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(consentService.decide(requestId, "", "", "DENY")).thenReturn(
                new AuthorizeDecisionResult.Denied(STABLE_CALLBACK, "state-1"));

        mockMvc.perform(post("/oauth/authorize/" + requestId + "/decision")
                        .param("loginId", "").param("password", "").param("decision", "DENY"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        STABLE_CALLBACK + "?error=access_denied&state=state-1" + ISS_PARAMETER));
    }

    @Test
    @DisplayName("로그인 실패 시 같은 요청의 재시도 화면을 보여준다")
    void decide_loginFailed_returnsRetryForm() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(consentService.decide(requestId, "user1", "invalid", "APPROVE")).thenReturn(
                new AuthorizeDecisionResult.LoginFailed(requestId, "ChatGPT", null,
                        List.of("catalog:read"), "아이디 또는 비밀번호가 올바르지 않습니다."));

        mockMvc.perform(post("/oauth/authorize/" + requestId + "/decision")
                        .param("loginId", "user1").param("password", "invalid").param("decision", "APPROVE"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("role=\"alert\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/oauth/authorize/" + requestId + "/decision")));
    }

    @Test
    @DisplayName("discovery는 실제 응답과 동일한 issuer와 RFC 9207 지원을 JSON으로 공개한다")
    void metadata_declaresIssuerIdentification() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://core.example.com/auth/"))
                .andExpect(jsonPath("$.authorization_response_iss_parameter_supported").value(true));
    }

    @Test
    @DisplayName("검증 통과 시 200과 로그인+동의 폼 HTML을 반환한다")
    void authorize_started_returnsHtmlForm() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(consentService.start(eq("chatgpt-plugin"), any(), eq("catalog:read"), eq("state-1"), any(), eq("S256")))
                .thenReturn(new AuthorizeStartResult.Started(requestId, "ChatGPT", null, List.of("catalog:read")));

        mockMvc.perform(get("/oauth/authorize")
                        .param("client_id", "chatgpt-plugin")
                        .param("redirect_uri", "https://chat.openai.com/callback")
                        .param("scope", "catalog:read")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/oauth/authorize/" + requestId + "/decision")));
    }

    @Test
    @DisplayName("scope가 허용 범위를 넘으면 redirect_uri로 error=invalid_scope를 돌려준다")
    void authorize_scopeRejected_redirectsWithError() throws Exception {
        when(consentService.start(any(), any(), any(), any(), any(), any())).thenReturn(
                new AuthorizeStartResult.ScopeRejected("https://chat.openai.com/callback", "state-1"));

        mockMvc.perform(get("/oauth/authorize")
                        .param("client_id", "chatgpt-plugin")
                        .param("redirect_uri", "https://chat.openai.com/callback")
                        .param("scope", "purchase:handoff")
                        .param("state", "state-1")
                        .param("code_challenge", "challenge")
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://chat.openai.com/callback?error=invalid_scope&state=state-1" + ISS_PARAMETER));
    }

    @Test
    @DisplayName("승인 완료 시 redirect_uri로 code와 state를 돌려준다")
    void decide_approved_redirectsWithCode() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(consentService.decide(requestId, "user1", "password", "APPROVE")).thenReturn(
                new AuthorizeDecisionResult.Approved("https://chat.openai.com/callback", "the-code", "state-1"));

        mockMvc.perform(post("/oauth/authorize/" + requestId + "/decision")
                        .param("loginId", "user1")
                        .param("password", "password")
                        .param("decision", "APPROVE"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://chat.openai.com/callback?code=the-code&state=state-1" + ISS_PARAMETER));
    }

    @Test
    @DisplayName("동의 거절 응답에도 iss와 원래 state를 전달한다")
    void decide_denied_redirectsWithIssuer() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(consentService.decide(requestId, "user1", "password", "DENY")).thenReturn(
                new AuthorizeDecisionResult.Denied(STABLE_CALLBACK, "state-1"));

        mockMvc.perform(post("/oauth/authorize/" + requestId + "/decision")
                        .param("loginId", "user1").param("password", "password").param("decision", "DENY"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        STABLE_CALLBACK + "?error=access_denied&state=state-1" + ISS_PARAMETER));
    }

    @Test
    @DisplayName("기존 콜백 쿼리와 특수 문자를 보존하고 issuer를 정규화 없이 인코딩한다")
    void decide_approved_encodesParametersAndPreservesExistingQuery() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(consentService.decide(requestId, "user1", "password", "APPROVE")).thenReturn(
                new AuthorizeDecisionResult.Approved(
                        STABLE_CALLBACK + "?existing=value%2B1", "code+/=", "state &+한글"));

        mockMvc.perform(post("/oauth/authorize/" + requestId + "/decision")
                        .param("loginId", "user1").param("password", "password").param("decision", "APPROVE"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", STABLE_CALLBACK
                        + "?existing=value%2B1&code=code%2B%2F%3D&state=state+%26%2B%ED%95%9C%EA%B8%80"
                        + ISS_PARAMETER));
    }

    @Test
    @DisplayName("신뢰할 수 없는 콜백은 iss를 포함한 리다이렉트 없이 직접 거부한다")
    void authorize_unregisteredCallback_doesNotRedirect() throws Exception {
        when(consentService.start(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AuthorizeStartResult.Rejected("등록되지 않은 리다이렉트 URI입니다."));

        mockMvc.perform(get("/oauth/authorize")
                        .param("client_id", "chatgpt-plugin").param("redirect_uri", "https://untrusted.example/callback")
                        .param("scope", "catalog:read").param("state", "state-1")
                        .param("code_challenge", "challenge").param("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("만료되거나 존재하지 않는 요청은 리다이렉트 없이 직접 거부한다")
    void decide_notFound_doesNotRedirect() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(consentService.decide(requestId, "user1", "password", "APPROVE"))
                .thenReturn(new AuthorizeDecisionResult.NotFound("요청이 만료되었습니다."));

        mockMvc.perform(post("/oauth/authorize/" + requestId + "/decision")
                        .param("loginId", "user1").param("password", "password").param("decision", "APPROVE"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"));
    }
}
