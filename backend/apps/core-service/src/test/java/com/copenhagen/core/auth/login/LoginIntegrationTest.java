package com.copenhagen.core.auth.login;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.copenhagen.core.auth.signup.SignupService;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoginFailureService#record}가 {@code REQUIRES_NEW} + {@code SELECT ... FOR UPDATE}로 동시 실패
 * 기록의 경쟁을 직렬화하는지 실 DB에서 검증한다(단위 테스트는 mock repository라 실제 행 잠금을 증명하지 못한다).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class LoginIntegrationTest {

    private static final String LOGIN_ID = "loginuser1";
    private static final String PASSWORD = "Password1!";

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private SignupService signupService;

    @Autowired
    private LoginService loginService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabaseAndSeedAccount() {
        jdbcTemplate.update(
                "TRUNCATE users, merchants, oauth_clients, idempotency_records RESTART IDENTITY CASCADE");
        signupService.signup(LOGIN_ID, PASSWORD, "login-integ@example.com", "홍길동");
    }

    @Test
    @DisplayName("정상 credential이면 로그인에 성공한다")
    void login_validCredentials_succeeds() {
        LoginResult result = loginService.login(LOGIN_ID, PASSWORD);

        assertEquals(LOGIN_ID, result.loginId());
    }

    @Test
    @DisplayName("동시에 5건의 틀린 비밀번호 요청이 들어와도 실패 횟수 유실 없이 정확히 5로 기록되고 잠긴다")
    void login_concurrentWrongPassword_recordsFailuresWithoutLostUpdates() throws Exception {
        int attempts = 5;
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                tasks.add(() -> {
                    startLine.await();
                    try {
                        loginService.login(LOGIN_ID, "WrongPassword1!");
                        return false;
                    } catch (CoreException exception) {
                        return exception.errorCode() == CoreErrorCode.AUTH_INVALID_CREDENTIALS;
                    }
                });
            }

            List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(executor.submit(task));
            }
            startLine.countDown();

            for (Future<Boolean> future : futures) {
                assertTrue(future.get(), "각 실패는 AUTH_INVALID_CREDENTIALS로 응답해야 한다");
            }
        } finally {
            executor.shutdown();
        }

        Integer failedLoginCount = jdbcTemplate.queryForObject(
                "SELECT failed_login_count FROM local_accounts WHERE login_id = ?", Integer.class, LOGIN_ID);
        assertEquals(attempts, failedLoginCount, "경쟁 상태로 실패 횟수가 유실되면 안 된다");

        Boolean isLocked = jdbcTemplate.queryForObject(
                "SELECT locked_until IS NOT NULL FROM local_accounts WHERE login_id = ?", Boolean.class, LOGIN_ID);
        assertTrue(isLocked, "5회째 실패로 잠겨 있어야 한다");
    }

    @Test
    @DisplayName("성공한 로그인은 실패 횟수·잠금을 DB에서 초기화한다")
    void login_success_resetsFailureCountInDatabase() {
        assertThrows(CoreException.class, () -> loginService.login(LOGIN_ID, "WrongPassword1!"));

        loginService.login(LOGIN_ID, PASSWORD);

        Integer failedLoginCount = jdbcTemplate.queryForObject(
                "SELECT failed_login_count FROM local_accounts WHERE login_id = ?", Integer.class, LOGIN_ID);
        assertEquals(0, failedLoginCount);
    }
}
