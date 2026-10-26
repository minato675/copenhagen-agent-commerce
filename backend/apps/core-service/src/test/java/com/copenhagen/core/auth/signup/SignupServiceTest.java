package com.copenhagen.core.auth.signup;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.copenhagen.core.auth.AuthConfiguration;
import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.crypto.PiiHasher;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupServiceTest {

    /** 공개된 개발/테스트 전용 값(application-test.yml과 동일). 운영에서 사용하지 않는다. */
    private static final String TEST_PII_KEY = "ZGV2LW9ubHktY29yZS1waWkta2V5LTMyLWJ5dGVzISE=";
    private static final String TEST_PII_PEPPER = "dev-only-core-pii-hash-pepper-value-do-not-use-in-prod";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private FakeUserRepository userRepository;
    private FakeLocalAccountRepository localAccountRepository;
    private PasswordEncoder passwordEncoder;
    private SignupService service;

    @BeforeEach
    void setUp() {
        userRepository = new FakeUserRepository();
        localAccountRepository = new FakeLocalAccountRepository();
        passwordEncoder = new BCryptPasswordEncoder(AuthConfiguration.BCRYPT_STRENGTH);
        service = new SignupService(
                userRepository,
                localAccountRepository,
                passwordEncoder,
                new PiiEncryptor(TEST_PII_KEY),
                new PiiHasher(TEST_PII_PEPPER),
                FIXED_CLOCK);
    }

    @Test
    @DisplayName("정상 요청이면 회원가입에 성공하고 결과를 반환한다")
    void signup_validRequest_succeeds() {
        SignupResult result = service.signup("user1234", "Password1!", "User@Example.com", "홍길동");

        assertNotNull(result.userId());
        assertEquals("user1234", result.loginId());
        assertEquals("user@example.com", result.email());
        assertEquals("홍길동", result.name());

        LocalAccount saved = localAccountRepository.findByLoginId("user1234").orElseThrow();
        assertTrue(passwordEncoder.matches("Password1!", saved.passwordHash()));
        assertTrue(saved.passwordHash().startsWith("$2a$10$"));
    }

    @Test
    @DisplayName("이름 앞뒤 공백은 trim되어 저장된다")
    void signup_nameWithSurroundingSpaces_trimmed() {
        SignupResult result = service.signup("user1234", "Password1!", "user@example.com", "  홍길동  ");

        assertEquals("홍길동", result.name());
    }

    @Test
    @DisplayName("이미 존재하는 loginId는 사전 확인에서 409로 거부된다")
    void signup_duplicateLoginId_preCheckRejects() {
        localAccountRepository.loginIds.add("user1234");

        CoreException exception = assertThrows(CoreException.class,
                () -> service.signup("user1234", "Password1!", "user@example.com", "홍길동"));

        assertEquals(CoreErrorCode.AUTH_LOGIN_ID_DUPLICATED, exception.errorCode());
    }

    @Test
    @DisplayName("이미 존재하는 email은 사전 확인에서 409로 거부된다")
    void signup_duplicateEmail_preCheckRejects() {
        service.signup("first-user", "Password1!", "user@example.com", "홍길동");

        CoreException exception = assertThrows(CoreException.class,
                () -> service.signup("second-user", "Password2@", "USER@EXAMPLE.COM", "김철수"));

        assertEquals(CoreErrorCode.AUTH_EMAIL_DUPLICATED, exception.errorCode());
    }

    @Test
    @DisplayName("DB UNIQUE 제약(email_hash) 위반은 AUTH_EMAIL_DUPLICATED로 매핑된다 (동시 가입 대비)")
    void signup_dbDuplicateKeyOnEmail_mapsToEmailDuplicated() {
        userRepository.duplicateKeyMessageOnInsert =
                "duplicate key value violates unique constraint \"uq_users_email_hash_active\"";

        CoreException exception = assertThrows(CoreException.class,
                () -> service.signup("user1234", "Password1!", "user@example.com", "홍길동"));

        assertEquals(CoreErrorCode.AUTH_EMAIL_DUPLICATED, exception.errorCode());
    }

    @Test
    @DisplayName("DB UNIQUE 제약(login_id) 위반은 AUTH_LOGIN_ID_DUPLICATED로 매핑된다 (동시 가입 대비)")
    void signup_dbDuplicateKeyOnLoginId_mapsToLoginIdDuplicated() {
        localAccountRepository.duplicateKeyMessageOnInsert =
                "duplicate key value violates unique constraint \"local_accounts_login_id_key\"";

        CoreException exception = assertThrows(CoreException.class,
                () -> service.signup("user1234", "Password1!", "user@example.com", "홍길동"));

        assertEquals(CoreErrorCode.AUTH_LOGIN_ID_DUPLICATED, exception.errorCode());
    }

    // ===== password 정책 5개 실패 조건 =====

    @ParameterizedTest
    @ValueSource(strings = {"Ab1!", "Ab1!Ab1!Ab1!Ab1!Ab1!x"})
    @DisplayName("password 길이가 8~20자를 벗어나면 거부한다")
    void signup_passwordLengthOutOfRange_rejected(String password) {
        assertPasswordRejected(password, "8~20자");
    }

    @Test
    @DisplayName("password에 공백·비ASCII 문자가 있으면 거부한다")
    void signup_passwordWithSpaceOrNonAscii_rejected() {
        assertPasswordRejected("Pass 123!", "공백");
        assertPasswordRejected("Pass한글123", "공백");
    }

    @Test
    @DisplayName("password가 영문/숫자/특수문자 중 1종만 포함하면 거부한다")
    void signup_passwordSingleCharacterClass_rejected() {
        assertPasswordRejected("abcdefgh", "2종");
    }

    @Test
    @DisplayName("password가 loginId와 대소문자 무시하고 같으면 거부한다")
    void signup_passwordEqualsLoginId_rejected() {
        CoreException exception = assertThrows(CoreException.class,
                () -> service.signup("myuser123", "MyUser123", "user@example.com", "홍길동"));

        assertTrue(exception.getMessage().contains("아이디와 동일"));
    }

    @Test
    @DisplayName("password에 3자 연속 반복/오름차순/내림차순이 있으면 거부한다")
    void signup_passwordWithRepeatingOrSequentialRun_rejected() {
        assertPasswordRejected("Passaaa1!", "반복 또는 연속");
        assertPasswordRejected("Passabc1!", "반복 또는 연속");
        assertPasswordRejected("Passcba1!", "반복 또는 연속");
    }

    @Test
    @DisplayName("영문·숫자 2종 조합만으로도 통과한다 (대문자 필수 아님, 연속·반복 없는 값)")
    void signup_passwordLowercaseAndDigitOnly_accepted() {
        SignupResult result = service.signup("user1234", "kx4mq7bt", "user@example.com", "홍길동");

        assertNotNull(result.userId());
    }

    private void assertPasswordRejected(String password, String expectedMessageFragment) {
        CoreException exception = assertThrows(CoreException.class,
                () -> service.signup("user1234", password, "user@example.com", "홍길동"));

        assertEquals(CoreErrorCode.INVALID_REQUEST, exception.errorCode());
        assertTrue(exception.getMessage().contains(expectedMessageFragment),
                "메시지에 '" + expectedMessageFragment + "'를 포함해야 한다: " + exception.getMessage());
    }

    // ===== 이름 길이 =====

    @Test
    @DisplayName("trim 후 20자를 초과하는 이름은 거부한다")
    void signup_nameTooLong_rejected() {
        String tooLong = "가".repeat(21);

        CoreException exception = assertThrows(CoreException.class,
                () -> service.signup("user1234", "Password1!", "user@example.com", tooLong));

        assertEquals(CoreErrorCode.INVALID_REQUEST, exception.errorCode());
    }

    // ===== 테스트 대역 =====

    private static class FakeUserRepository implements UserRepository {

        private final Map<Long, User> byId = new HashMap<>();
        private final Set<String> existingEmailHashes = new HashSet<>();
        private long nextId = 1;
        private String duplicateKeyMessageOnInsert;

        @Override
        public long insert(UUID publicId, String name, UserStatus status,
                EncryptedPii emailEncrypted, String emailHash, Instant now) {
            if (duplicateKeyMessageOnInsert != null) {
                throw new DuplicateKeyException(duplicateKeyMessageOnInsert);
            }
            long id = nextId++;
            existingEmailHashes.add(emailHash);
            byId.put(id, new User(id, publicId, name, status, emailEncrypted, emailHash, false,
                    null, null, null, null, null, now, now));
            return id;
        }

        @Override
        public boolean existsByEmailHash(String emailHash) {
            return existingEmailHashes.contains(emailHash);
        }

        @Override
        public Optional<User> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<User> findByPublicId(UUID publicId) {
            return byId.values().stream().filter(u -> u.publicId().equals(publicId)).findFirst();
        }

        @Override
        public void updateLastLoginAt(long id, Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeLocalAccountRepository implements LocalAccountRepository {

        private final Map<String, LocalAccount> byLoginId = new HashMap<>();
        private final Set<String> loginIds = new HashSet<>();
        private long nextId = 1;
        private String duplicateKeyMessageOnInsert;

        @Override
        public boolean existsByLoginId(String loginId) {
            return loginIds.contains(loginId);
        }

        @Override
        public Optional<LocalAccount> findByLoginId(String loginId) {
            return Optional.ofNullable(byLoginId.get(loginId));
        }

        @Override
        public Optional<LocalAccount> findByIdForUpdate(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LocalAccount> findByUserId(long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long insert(long userId, String loginId, String passwordHash, Instant now) {
            if (duplicateKeyMessageOnInsert != null) {
                throw new DuplicateKeyException(duplicateKeyMessageOnInsert);
            }
            long id = nextId++;
            loginIds.add(loginId);
            byLoginId.put(loginId,
                    new LocalAccount(id, userId, loginId, passwordHash, 0, null, null, now, now));
            return id;
        }

        @Override
        public void recordLoginFailure(
                long id, int failedLoginCount, Instant lastFailedLoginAt, Instant lockedUntil, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resetLoginFailure(long id, Instant now) {
            throw new UnsupportedOperationException();
        }
    }
}
