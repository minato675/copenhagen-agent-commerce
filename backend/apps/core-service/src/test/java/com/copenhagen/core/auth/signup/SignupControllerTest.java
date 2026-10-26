package com.copenhagen.core.auth.signup;

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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code CoreAuthenticationFilter}는 {@code @WebMvcTest}가 필터로 함께 로드하는데, 이 슬라이스에는
 * {@code CoreJwtVerifier} 같은 {@code @Service} 빈이 없어 그대로 두면 컨텍스트 로딩이 실패한다 — 이 컨트롤러
 * 테스트와 무관하므로 제외한다.
 */
@WebMvcTest(
        controllers = SignupController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class SignupControllerTest {

    private static final String VALID_BODY = """
            {"loginId":"user1234","password":"Password1!","email":"user@example.com","name":"홍길동"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SignupService signupService;

    @Test
    @DisplayName("정상 요청이면 201과 명세대로의 응답 필드를 반환한다")
    void signup_validRequest_returns201WithExpectedFields() throws Exception {
        UUID userId = UUID.randomUUID();
        when(signupService.signup(eq("user1234"), eq("Password1!"), eq("user@example.com"), eq("홍길동")))
                .thenReturn(new SignupResult(userId, "user1234", "user@example.com", "홍길동"));

        mockMvc.perform(post("/api/core/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.loginId").value("user1234"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.name").value("홍길동"));
    }

    @Test
    @DisplayName("loginId 형식이 잘못되면 @Valid에 걸려 400 INVALID_REQUEST를 반환한다")
    void signup_invalidLoginIdFormat_returns400() throws Exception {
        String body = """
                {"loginId":"AB","password":"Password1!","email":"user@example.com","name":"홍길동"}
                """;

        mockMvc.perform(post("/api/core/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(content().string(containsString("\"currentState\":null")));
    }

    @Test
    @DisplayName("loginId 중복이면 409 AUTH_LOGIN_ID_DUPLICATED를 반환한다")
    void signup_duplicateLoginId_returns409() throws Exception {
        when(signupService.signup(any(), any(), any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.AUTH_LOGIN_ID_DUPLICATED));

        mockMvc.perform(post("/api/core/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AUTH_LOGIN_ID_DUPLICATED"))
                .andExpect(jsonPath("$.nextAction").value("USE_DIFFERENT_LOGIN_ID"));
    }

    @Test
    @DisplayName("email 중복이면 409 AUTH_EMAIL_DUPLICATED를 반환한다")
    void signup_duplicateEmail_returns409() throws Exception {
        when(signupService.signup(any(), any(), any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.AUTH_EMAIL_DUPLICATED));

        mockMvc.perform(post("/api/core/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AUTH_EMAIL_DUPLICATED"));
    }
}
