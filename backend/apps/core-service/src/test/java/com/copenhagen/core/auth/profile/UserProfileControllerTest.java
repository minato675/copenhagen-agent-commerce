package com.copenhagen.core.auth.profile;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.common.auth.CoreAuthenticationFilter;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.error.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code CoreAuthenticationFilter} 제외 이유는 {@code LoginControllerTest}와 같다. */
@WebMvcTest(
        controllers = UserProfileController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class UserProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService userProfileService;

    @Test
    @DisplayName("인증된 요청이면 200과 userId/loginId/name/email 4필드를 반환한다")
    void me_authenticated_returns200WithExpectedFields() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userProfileService.getProfile(eq(userId)))
                .thenReturn(new UserProfileResult(userId, "user1234", "홍길동", "user@example.com"));

        mockMvc.perform(get("/api/core/v1/users/me")
                        .with(request -> {
                            CoreRequestContext.setPrincipal(request, userId);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.loginId").value("user1234"))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.*", hasSize(4)));
    }

    @Test
    @DisplayName("필터를 거치지 않아 principal이 없으면 401 AUTH_TOKEN_MISSING을 반환한다")
    void me_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/core/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_MISSING"));
    }
}
