package com.copenhagen.core.auth.login;

import java.util.UUID;

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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code CoreAuthenticationFilter}는 {@code @WebMvcTest}가 필터로 함께 로드하는데, 이 슬라이스에는
 * {@code CoreJwtVerifier} 같은 {@code @Service} 빈이 없어 그대로 두면 컨텍스트 로딩이 실패한다 — 이 컨트롤러
 * 테스트와 무관하므로 제외한다.
 */
@WebMvcTest(
        controllers = LoginController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class LoginControllerTest {

    private static final String VALID_BODY = """
            {"loginId":"user1234","password":"Password1!","deviceId":"device-uuid-abc","deviceName":"iPhone 15"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthLoginService authLoginService;

    @Test
    @DisplayName("정상 로그인이면 200과 명세대로의 토큰·user 필드를 반환한다")
    void login_validRequest_returns200WithExpectedFields() throws Exception {
        UUID userId = UUID.randomUUID();
        LoginResult loginResult = new LoginResult(userId, 42L, "user1234", "홍길동");
        AuthLoginOutcome outcome = new AuthLoginOutcome("access-token", "refresh-token", 1800L, loginResult);
        when(authLoginService.login(
                        eq("user1234"), eq("Password1!"), eq("device-uuid-abc"), eq("iPhone 15")))
                .thenReturn(outcome);

        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.user.userId").value(userId.toString()))
                .andExpect(jsonPath("$.user.loginId").value("user1234"))
                .andExpect(jsonPath("$.user.name").value("홍길동"));
    }

    @Test
    @DisplayName("deviceId가 없으면 @Valid에 걸려 400 INVALID_REQUEST를 반환한다")
    void login_missingDeviceId_returns400() throws Exception {
        String body = """
                {"loginId":"user1234","password":"Password1!","deviceName":"iPhone 15"}
                """;

        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("자격 증명이 틀리면 401 AUTH_INVALID_CREDENTIALS를 반환한다")
    void login_invalidCredentials_returns401() throws Exception {
        when(authLoginService.login(eq("user1234"), eq("Password1!"), eq("device-uuid-abc"), eq("iPhone 15")))
                .thenThrow(new CoreException(CoreErrorCode.AUTH_INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("계정이 비활성이면 403 AUTH_ACCOUNT_UNAVAILABLE을 반환한다")
    void login_inactiveAccount_returns403() throws Exception {
        when(authLoginService.login(eq("user1234"), eq("Password1!"), eq("device-uuid-abc"), eq("iPhone 15")))
                .thenThrow(new CoreException(CoreErrorCode.AUTH_ACCOUNT_UNAVAILABLE));

        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_UNAVAILABLE"));
    }

    @Test
    @DisplayName("잠긴 계정이면 423과 retryAfterSeconds를 반환한다")
    void login_lockedAccount_returns423WithRetryAfterSeconds() throws Exception {
        when(authLoginService.login(eq("user1234"), eq("Password1!"), eq("device-uuid-abc"), eq("iPhone 15")))
                .thenThrow(new CoreException(
                        CoreErrorCode.AUTH_LOGIN_LOCKED, "잠김", null, "TRY_AGAIN_LATER", 900L, null));

        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.errorCode").value("AUTH_LOGIN_LOCKED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(900));
    }
}
