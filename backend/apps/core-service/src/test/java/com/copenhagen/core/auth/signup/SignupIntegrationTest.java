package com.copenhagen.core.auth.signup;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class SignupIntegrationTest {

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update(
                "TRUNCATE users, merchants, oauth_clients, idempotency_records RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("정상 가입은 201과 함께 users·local_accounts에 실제로 저장되고 이메일이 평문으로 남지 않는다")
    void signup_success_persistsEncryptedAndReturns201() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("integuser1", "Password1!", "integ1@example.com", "홍길동")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginId").value("integuser1"))
                .andExpect(jsonPath("$.email").value("integ1@example.com"));

        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        assertEquals(1, userCount);

        Integer localAccountCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM local_accounts", Integer.class);
        assertEquals(1, localAccountCount);

        byte[] emailEncrypted = jdbcTemplate.queryForObject(
                "SELECT email_encrypted FROM users LIMIT 1", byte[].class);
        String rawStoredBytes = new String(emailEncrypted);
        assertTrue(!rawStoredBytes.contains("integ1@example.com"), "이메일 평문이 email_encrypted 컬럼에 그대로 남아 있으면 안 된다");

        String passwordHash =
                jdbcTemplate.queryForObject("SELECT password_hash FROM local_accounts LIMIT 1", String.class);
        assertNotEquals("Password1!", passwordHash);
        assertTrue(passwordHash.startsWith("$2a$10$"));
    }

    @Test
    @DisplayName("이미 가입된 loginId로 다시 요청하면 409 AUTH_LOGIN_ID_DUPLICATED를 반환한다")
    void signup_preExistingLoginId_returns409() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dupuser1", "Password1!", "first@example.com", "홍길동")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/core/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dupuser1", "Password2@", "second@example.com", "김철수")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AUTH_LOGIN_ID_DUPLICATED"));

        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        assertEquals(1, userCount);
    }

    @Test
    @DisplayName("이미 가입된 email로 다시 요청하면 409 AUTH_EMAIL_DUPLICATED를 반환한다")
    void signup_preExistingEmail_returns409() throws Exception {
        mockMvc.perform(post("/api/core/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("emailuser1", "Password1!", "shared@example.com", "홍길동")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/core/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("emailuser2", "Password2@", "SHARED@example.com", "김철수")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AUTH_EMAIL_DUPLICATED"));
    }

    @Test
    @DisplayName("동일 loginId로 동시에 2개 요청이 들어오면 1명만 성공하고 패자의 users row는 남지 않는다")
    void signup_concurrentSameLoginId_onlyOneWinnerSurvives() throws Exception {
        String loginId = "raceuser1";
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> firstAttempt = raceAttempt(startLine, loginId, "race1@example.com");
            Callable<Integer> secondAttempt = raceAttempt(startLine, loginId, "race2@example.com");

            Future<Integer> first = executor.submit(firstAttempt);
            Future<Integer> second = executor.submit(secondAttempt);
            startLine.countDown();

            List<Integer> statuses = List.of(first.get(), second.get());
            long successCount = statuses.stream().filter(status -> status == 201).count();
            long conflictCount = statuses.stream().filter(status -> status == 409).count();

            assertEquals(1, successCount, "동시 요청 중 정확히 1건만 성공해야 한다: " + statuses);
            assertEquals(1, conflictCount, "동시 요청 중 정확히 1건은 409여야 한다: " + statuses);
        } finally {
            executor.shutdown();
        }

        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        assertEquals(1, userCount, "패자의 users row는 트랜잭션 롤백으로 남지 않아야 한다");

        Integer localAccountCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM local_accounts WHERE login_id = ?", Integer.class, loginId);
        assertEquals(1, localAccountCount);
    }

    private Callable<Integer> raceAttempt(CountDownLatch startLine, String loginId, String email) {
        return () -> {
            startLine.await();
            MvcResult result = mockMvc.perform(post("/api/core/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(loginId, "Password1!", email, "동시가입")))
                    .andReturn();
            return result.getResponse().getStatus();
        };
    }

    private static String body(String loginId, String password, String email, String name) {
        return "{\"loginId\":\"%s\",\"password\":\"%s\",\"email\":\"%s\",\"name\":\"%s\"}"
                .formatted(loginId, password, email, name);
    }
}
