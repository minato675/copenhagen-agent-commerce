package com.copenhagen.core.agent.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.common.auth.CoreAuthenticationFilter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 제외 이유는 {@code OauthAuthorizeControllerTest}와 같다. */
@WebMvcTest(
        controllers = OauthTokenController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
class OauthTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OauthTokenService tokenService;

    @Test
    @DisplayName("성공 응답은 OAuth 표준 snake_case 필드로 내려간다")
    void token_success_returnsSnakeCaseBody() throws Exception {
        when(tokenService.handle(any())).thenReturn(
                new TokenResult.Success("access-token-value", "refresh-token-value", 300L, "catalog:read"));

        mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", "the-code")
                        .param("redirect_uri", "https://chat.openai.com/callback")
                        .param("client_id", "chatgpt-plugin")
                        .param("code_verifier", "verifier"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("access-token-value"))
                .andExpect(jsonPath("$.refresh_token").value("refresh-token-value"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(300))
                .andExpect(jsonPath("$.scope").value("catalog:read"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("오류 응답은 지정한 HTTP status와 error/error_description을 반환한다")
    void token_error_returnsErrorBody() throws Exception {
        when(tokenService.handle(any())).thenReturn(
                new TokenResult.Error(HttpStatus.BAD_REQUEST, "invalid_grant", "유효하지 않거나 만료된 코드입니다."));

        mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", "bad-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }
}
