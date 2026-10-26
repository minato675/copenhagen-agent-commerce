package com.copenhagen.core.auth.login;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.auth.signup.SignupService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/core/v1/auth/login}이 실제로 토큰을 발급·저장하고 DB를 갱신하는지 실 PostgreSQL에서 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class LoginApiIntegrationTest {

    private static final String LOGIN_ID = "apilogin1";
    private static final String PASSWORD = "Password1!";

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignupService signupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabaseAndSeedAccount() {
        jdbcTemplate.update(
                "TRUNCATE users, merchants, oauth_clients, idempotency_records RESTART IDENTITY CASCADE");
        signupService.signup(LOGIN_ID, PASSWORD, "api-login@example.com", "홍길동");
    }

    @Test
    @DisplayName("정상 로그인은 200과 토큰을 반환하고, last_login_at·refresh_tokens를 실제로 갱신한다")
    void login_success_persistsTokensAndUpdatesLastLoginAt() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LOGIN_ID, PASSWORD, "device-1", "iPhone 15")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.user.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.user.name").value("홍길동"));

        Object lastLoginAt = jdbcTemplate.queryForObject(
                "SELECT last_login_at FROM users u JOIN local_accounts la ON la.user_id = u.id "
                        + "WHERE la.login_id = ?",
                Object.class,
                LOGIN_ID);
        assertNotNull(lastLoginAt);

        Integer refreshTokenCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens rt JOIN local_accounts la ON la.user_id = rt.user_id "
                        + "WHERE la.login_id = ? AND rt.device_id = 'device-1'",
                Integer.class,
                LOGIN_ID);
        assertEquals(1, refreshTokenCount);

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT rt.token_hash FROM refresh_tokens rt JOIN local_accounts la ON la.user_id = rt.user_id "
                        + "WHERE la.login_id = ? AND rt.device_id = 'device-1'",
                String.class,
                LOGIN_ID);
        assertEquals(64, storedHash.trim().length(), "SHA-256 해시는 64자 hex여야 한다");
    }

    @Test
    @DisplayName("같은 (user, device)로 재로그인하면 이전 Refresh Token이 폐기된다")
    void login_sameDeviceTwice_revokesPreviousRefreshToken() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LOGIN_ID, PASSWORD, "device-1", "iPhone 15")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LOGIN_ID, PASSWORD, "device-1", "iPhone 15")))
                .andExpect(status().isOk());

        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens rt JOIN local_accounts la ON la.user_id = rt.user_id "
                        + "WHERE la.login_id = ? AND rt.device_id = 'device-1'",
                Integer.class,
                LOGIN_ID);
        assertEquals(2, totalRows);

        Integer activeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens rt JOIN local_accounts la ON la.user_id = rt.user_id "
                        + "WHERE la.login_id = ? AND rt.device_id = 'device-1' AND rt.revoked_at IS NULL",
                Integer.class,
                LOGIN_ID);
        assertEquals(1, activeRows, "활성 토큰은 최신 1건만 남아야 한다");
    }

    @Test
    @DisplayName("틀린 비밀번호는 401 AUTH_INVALID_CREDENTIALS를 반환하고 토큰을 발급하지 않는다")
    void login_wrongPassword_returns401AndIssuesNoToken() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LOGIN_ID, "WrongPassword1!", "device-1", "iPhone 15")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));

        Integer refreshTokenCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens", Integer.class);
        assertEquals(0, refreshTokenCount);

        Object lastLoginAt = jdbcTemplate.queryForObject(
                "SELECT last_login_at FROM users u JOIN local_accounts la ON la.user_id = u.id "
                        + "WHERE la.login_id = ?",
                Object.class,
                LOGIN_ID);
        assertNull(lastLoginAt);
    }

    @Test
    @DisplayName("5회 실패로 잠긴 계정은 423과 retryAfterSeconds를 반환한다")
    void login_lockedAccount_returns423WithRetryAfterSeconds() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/core/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(LOGIN_ID, "WrongPassword1!", "device-1", "iPhone 15")));
        }

        mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LOGIN_ID, PASSWORD, "device-1", "iPhone 15")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.errorCode").value("AUTH_LOGIN_LOCKED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    private static String body(String loginId, String password, String deviceId, String deviceName) {
        return "{\"loginId\":\"%s\",\"password\":\"%s\",\"deviceId\":\"%s\",\"deviceName\":\"%s\"}"
                .formatted(loginId, password, deviceId, deviceName);
    }
}
