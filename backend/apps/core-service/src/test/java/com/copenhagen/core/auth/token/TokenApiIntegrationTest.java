package com.copenhagen.core.auth.token;

import com.jayway.jsonpath.JsonPath;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/core/v1/auth/token/refresh}와 {@code POST /api/core/v1/auth/logout}의 Rotation·재사용
 * 탐지·폐기 범위를 실 PostgreSQL에서 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class TokenApiIntegrationTest {

    private static final String LOGIN_ID = "apitoken1";
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
        signupService.signup(LOGIN_ID, PASSWORD, "api-token@example.com", "홍길동");
    }

    @Test
    @DisplayName("갱신하면 새 토큰이 발급되고 옛 행은 폐기되어 기기당 활성 토큰은 1건으로 유지된다")
    void refresh_rotatesToken_keepsSingleActiveTokenPerDevice() throws Exception {
        String first = login("device-1");
        String second = refresh(first);
        String third = refresh(second);

        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertEquals(3, countTokens("device-1"));
        assertEquals(1, countActiveTokens("device-1"), "활성 토큰은 최신 1건만 남아야 한다");
    }

    @Test
    @DisplayName("회전된 옛 토큰으로 갱신하면 재사용으로 보고 해당 사용자의 모든 기기 토큰을 폐기한다")
    void refresh_withRotatedToken_revokesEveryDeviceToken() throws Exception {
        String firstOnDevice1 = login("device-1");
        login("device-2");
        refresh(firstOnDevice1);

        assertEquals(2, countAllActiveTokens(), "회전 직후에는 두 기기 모두 활성이어야 한다");

        mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(firstOnDevice1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_REFRESH_TOKEN_REUSED"));

        assertEquals(0, countAllActiveTokens(), "탈취 의심 시 전 기기 토큰이 폐기되어야 한다");
    }

    @Test
    @DisplayName("발급된 적 없는 토큰으로 갱신하면 401 AUTH_REFRESH_TOKEN_INVALID다")
    void refresh_withUnknownToken_returns401Invalid() throws Exception {
        login("device-1");

        mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody("never-issued-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_REFRESH_TOKEN_INVALID"));

        assertEquals(1, countAllActiveTokens(), "미등록 토큰은 기존 세션에 영향을 주지 않는다");
    }

    @Test
    @DisplayName("정지된 계정은 403 AUTH_ACCOUNT_UNAVAILABLE이며 기존 토큰을 폐기하지 않는다")
    void refresh_suspendedAccount_returns403AndKeepsToken() throws Exception {
        String token = login("device-1");
        jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED'");

        mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_UNAVAILABLE"));

        assertEquals(1, countActiveTokens("device-1"), "상태 검사로 거부된 토큰은 폐기하지 않는다");
    }

    @Test
    @DisplayName("로그아웃은 해당 기기 토큰만 폐기하고 다른 기기 세션은 유지한다")
    void logout_revokesOnlyRequestedDeviceToken() throws Exception {
        String device1 = login("device-1");
        login("device-2");

        mockMvc.perform(post("/api/core/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(device1)))
                .andExpect(status().isNoContent());

        assertEquals(0, countActiveTokens("device-1"));
        assertEquals(1, countActiveTokens("device-2"), "다른 기기 세션은 유지되어야 한다");
    }

    @Test
    @DisplayName("발급된 적 없는 토큰으로 로그아웃해도 204이고 아무것도 폐기하지 않는다(멱등)")
    void logout_withUnknownToken_returns204AndChangesNothing() throws Exception {
        login("device-1");

        mockMvc.perform(post("/api/core/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody("never-issued-token")))
                .andExpect(status().isNoContent());

        assertEquals(1, countAllActiveTokens());
    }

    /**
     * 로그아웃한 토큰으로 갱신하면 재사용 탐지에 걸려 전 기기가 폐기된다. {@code revoked_at}만으로는 "로그아웃
     * 폐기"와 "회전 폐기"를 구분할 수 없어서 생기는, 설계에서 수용하기로 한 동작이다 — 의도치 않게 바뀌지 않도록
     * 테스트로 고정한다.
     */
    @Test
    @DisplayName("로그아웃한 토큰으로 갱신하면 재사용으로 판정된다(수용된 한계)")
    void refresh_withLoggedOutToken_isTreatedAsReuse() throws Exception {
        String token = login("device-1");

        mockMvc.perform(post("/api/core/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_REFRESH_TOKEN_REUSED"));
    }

    private String login(String deviceId) throws Exception {
        String response = mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"%s\",\"password\":\"%s\",\"deviceId\":\"%s\",\"deviceName\":\"%s\"}"
                                .formatted(LOGIN_ID, PASSWORD, deviceId, deviceId + " device")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return readRefreshToken(response);
    }

    private String refresh(String refreshToken) throws Exception {
        String response = mockMvc.perform(post("/api/core/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return readRefreshToken(response);
    }

    private static String readRefreshToken(String response) {
        return JsonPath.read(response, "$.refreshToken");
    }

    private static String tokenBody(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
    }

    private int countTokens(String deviceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE device_id = ?", Integer.class, deviceId);
    }

    private int countActiveTokens(String deviceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE device_id = ? AND revoked_at IS NULL",
                Integer.class,
                deviceId);
    }

    private int countAllActiveTokens() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class);
    }
}
