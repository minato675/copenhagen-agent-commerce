package com.copenhagen.core.auth.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.common.auth.CoreAuthenticationFilter;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.common.error.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code CoreAuthenticationFilter} 제외 이유는 {@code LoginControllerTest}와 같다. */
@WebMvcTest(
        controllers = AuthTokenController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class AuthTokenControllerTest {

    private static final String REFRESH_TOKEN = "stored-refresh-token";
    private static final String VALID_BODY = """
            {"refreshToken":"stored-refresh-token"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthTokenService authTokenService;

    @Test
    @DisplayName("정상 갱신이면 200과 명세의 3필드를 반환한다")
    void refresh_validRequest_returns200WithExpectedFields() throws Exception {
        when(authTokenService.refresh(eq(REFRESH_TOKEN)))
                .thenReturn(new TokenRefreshOutcome("new-access-token", "new-refresh-token", 1800L));

        mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"))
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    @DisplayName("없거나 만료된 토큰이면 401 AUTH_REFRESH_TOKEN_INVALID를 오류 응답으로 반환한다")
    void refresh_invalidToken_returns401() throws Exception {
        when(authTokenService.refresh(eq(REFRESH_TOKEN)))
                .thenThrow(new CoreException(CoreErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_REFRESH_TOKEN_INVALID"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.nextAction").value("LOGIN_REQUIRED"));
    }

    @Test
    @DisplayName("폐기된 토큰 재사용이면 401 AUTH_REFRESH_TOKEN_REUSED를 반환한다")
    void refresh_reusedToken_returns401Reused() throws Exception {
        when(authTokenService.refresh(eq(REFRESH_TOKEN)))
                .thenThrow(new CoreException(CoreErrorCode.AUTH_REFRESH_TOKEN_REUSED));

        mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_REFRESH_TOKEN_REUSED"));
    }

    @Test
    @DisplayName("이용할 수 없는 계정이면 403 AUTH_ACCOUNT_UNAVAILABLE을 반환한다")
    void refresh_inactiveAccount_returns403() throws Exception {
        when(authTokenService.refresh(eq(REFRESH_TOKEN)))
                .thenThrow(new CoreException(CoreErrorCode.AUTH_ACCOUNT_UNAVAILABLE));

        mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_UNAVAILABLE"));
    }

    @Test
    @DisplayName("refreshToken이 없으면 @Valid에 걸려 400 INVALID_REQUEST를 반환한다")
    void refresh_missingRefreshToken_returns400() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("로그아웃 성공은 204와 빈 바디다")
    void logout_validRequest_returns204() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());

        verify(authTokenService).logout(REFRESH_TOKEN);
    }

    @Test
    @DisplayName("이미 폐기됐거나 없는 토큰으로 로그아웃해도 204다(멱등)")
    void logout_unknownToken_returns204() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"never-issued"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("로그아웃도 refreshToken이 없으면 400 INVALID_REQUEST를 반환한다")
    void logout_missingRefreshToken_returns400() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verifyNoInteractions(authTokenService);
    }
}
