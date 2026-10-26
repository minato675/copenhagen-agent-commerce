package com.copenhagen.core.auth.profile;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/core/v1/users/me}가 순서 10에서 처음 생기는 실제 보호 라우트임을 실 PostgreSQL로 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class UserProfileApiIntegrationTest {

    private static final String LOGIN_ID = "apiprofile1";
    private static final String PASSWORD = "Password1!";
    private static final String NAME = "홍길동";
    private static final String EMAIL = "api-profile@example.com";

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
        signupService.signup(LOGIN_ID, PASSWORD, EMAIL, NAME);
    }

    @Test
    @DisplayName("유효한 Access Token이면 가입 시 저장한 loginId/name/email을 그대로 반환한다")
    void me_validAccessToken_returnsSignedUpProfile() throws Exception {
        String accessToken = login(LOGIN_ID);

        mockMvc.perform(get("/api/core/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(LOGIN_ID))
                .andExpect(jsonPath("$.name").value(NAME))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    @DisplayName("대소문자·공백이 섞인 이메일로 가입해도 정규화된 이메일을 반환한다")
    void me_mixedCaseEmail_returnsNormalizedEmail() throws Exception {
        String loginId = "apiprofile2";
        signupService.signup(loginId, PASSWORD, "  API-Profile2@Example.COM  ", NAME);

        mockMvc.perform(get("/api/core/v1/users/me")
                        .header("Authorization", "Bearer " + login(loginId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("api-profile2@example.com"));
    }

    /** {@code @Size(max = 254)} 상한과 같은 길이. 경계 길이에서도 암호화 저장·복호화 왕복이 깨지지 않는지 본다. */
    @Test
    @DisplayName("254자 경계 길이 이메일도 그대로 복호화해 반환한다")
    void me_maxLengthEmail_returnsSameEmail() throws Exception {
        String loginId = "apiprofile3";
        String email = "a".repeat(64) + "@" + "b".repeat(185) + ".com";
        assertEquals(254, email.length());
        signupService.signup(loginId, PASSWORD, email, NAME);

        mockMvc.perform(get("/api/core/v1/users/me")
                        .header("Authorization", "Bearer " + login(loginId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    /**
     * 테스트항목 §2: 프레임워크 레벨 예외가 catch-all에 걸려 500으로 삼켜지지 않는지 본다.
     *
     * <p>현재 develop은 상태 코드만 405이고 본문은 비어 있다. {@code GlobalExceptionHandler}에
     * {@code HttpRequestMethodNotSupportedException} 핸들러가 있는데도 그런 이유는
     * {@code @RestControllerAdvice(basePackages = ...)} 셀렉터다. 핸들러 매핑 단계 예외는
     * {@code handlerType == null}이라 advice가 적용되지 않는다
     * ({@code log/1단계_코드리뷰_대응결과.md} 결함 2와 같은 원인이며, 그 대응은 develop에 반영돼 있지 않다).
     * 이슈 412 범위 밖이므로 여기서는 500으로 삼켜지지 않는 것까지만 고정한다.
     */
    @Test
    @DisplayName("GET만 허용하므로 POST는 500이 아니라 405다")
    void me_postMethod_returns405() throws Exception {
        mockMvc.perform(post("/api/core/v1/users/me")
                        .header("Authorization", "Bearer " + login(LOGIN_ID)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("성공 응답에 Set-Cookie·Authorization 같은 헤더를 싣지 않는다")
    void me_successResponse_hasNoSessionHeaders() throws Exception {
        mockMvc.perform(get("/api/core/v1/users/me")
                        .header("Authorization", "Bearer " + login(LOGIN_ID)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("Authorization"));
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 401 AUTH_TOKEN_MISSING이다")
    void me_missingHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/core/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_MISSING"));
    }

    @Test
    @DisplayName("위조되거나 만료된 토큰이면 401 AUTH_TOKEN_INVALID다")
    void me_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/core/v1/users/me")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_INVALID"));
    }

    private String login(String loginId) throws Exception {
        String body = """
                {"loginId":"%s","password":"%s","deviceId":"device-1","deviceName":"iPhone 15"}
                """.formatted(loginId, PASSWORD);
        String response = mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
