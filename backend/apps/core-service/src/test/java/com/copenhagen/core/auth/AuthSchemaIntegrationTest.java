package com.copenhagen.core.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.oauthaccount.OauthAccount;
import com.copenhagen.core.auth.oauthaccount.OauthAccountRepository;
import com.copenhagen.core.auth.oauthaccount.OauthProvider;
import com.copenhagen.core.auth.refreshtoken.RefreshToken;
import com.copenhagen.core.auth.refreshtoken.RefreshTokenRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.crypto.PiiHasher;
import com.copenhagen.core.common.crypto.PiiNormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class AuthSchemaIntegrationTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = FIXED_CLOCK.instant();

    /** core ERD v5.1이 정의한 21개 테이블. */
    private static final List<String> ERD_TABLES = List.of(
            "users", "local_accounts", "oauth_accounts", "refresh_tokens",
            "merchants", "carts", "cart_items", "purchase_requests", "merchant_checkouts",
            "checkout_snapshots", "checkout_snapshot_items", "checkout_fulfillment_option_snapshots",
            "orders", "payment_transactions", "idempotency_records", "fraud_assessments",
            "fcm_device_tokens", "addresses", "oauth_clients", "agent_connections", "agent_refresh_tokens");

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocalAccountRepository localAccountRepository;

    @Autowired
    private OauthAccountRepository oauthAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PiiEncryptor piiEncryptor;

    @Autowired
    private PiiHasher piiHasher;

    @BeforeEach
    void cleanDatabase() {
        // users를 CASCADE로 지우면 FK로 연결된 나머지 테이블도 함께 비워진다.
        jdbcTemplate.update(
                "TRUNCATE users, merchants, oauth_clients, idempotency_records RESTART IDENTITY CASCADE");
    }

    // =====================================================================
    // 스키마 형태
    // =====================================================================

    @Test
    @DisplayName("ERD v5.1의 21개 테이블이 모두 존재한다")
    void schema_containsAllErdTables() {
        for (String table : ERD_TABLES) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertEquals(1, count, table + " 테이블이 없다");
        }
    }

    @Test
    @DisplayName("복수화 이전의 옛 테이블명은 더 이상 존재하지 않는다")
    void schema_oldSingularTableNames_areGone() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name IN ('local_account', 'oauth_account', 'refresh_token')
                """,
                Integer.class);
        assertEquals(0, count);
    }

    @Test
    @DisplayName("users에는 더 이상 평문 email·phone_number 컬럼이 없다")
    void users_plaintextPiiColumns_areGone() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'users' AND column_name IN ('email', 'phone_number')
                """,
                Integer.class);
        assertEquals(0, count);
    }

    @Test
    @DisplayName("users의 PII 컬럼은 BYTEA와 CHAR(64)다")
    void users_piiColumns_haveExpectedTypes() {
        assertEquals("bytea", columnType("users", "email_encrypted"));
        assertEquals("bytea", columnType("users", "phone_encrypted"));
        assertEquals("character", columnType("users", "email_hash"));
        assertEquals("character", columnType("users", "phone_hash"));
    }

    @Test
    @DisplayName("이름이 바뀐 제약조건과 인덱스가 새 이름으로 존재한다")
    void schema_renamedConstraintsAndIndexes_useNewNames() {
        assertTrue(constraintExists("uq_oauth_accounts_provider_subject"));
        assertTrue(constraintExists("uq_oauth_accounts_user_provider"));
        assertTrue(constraintExists("fk_local_accounts_user"));
        assertTrue(constraintExists("ck_refresh_tokens_expiry"));
        assertTrue(indexExists("idx_refresh_tokens_expires_at"));

        assertFalse(constraintExists("uq_oauth_account_provider_subject"));
        assertFalse(constraintExists("fk_local_account_user"));
        assertFalse(indexExists("idx_refresh_token_expires_at"));
    }

    // =====================================================================
    // 저장·조회
    // =====================================================================

    @Test
    @DisplayName("네 테이블 모두 저장한 값을 그대로 조회할 수 있다")
    void repositories_insertThenFind_returnsSameValues() {
        long userId = insertUser("user1@example.com");
        UUID publicId = userRepository.findById(userId).orElseThrow().publicId();

        long localAccountId = localAccountRepository.insert(userId, "user1", "hashed-password", NOW);
        long oauthAccountId = oauthAccountRepository.insert(userId, OauthProvider.GOOGLE, "google-subject-1", NOW);
        long refreshTokenId = refreshTokenRepository.insert(
                userId, tokenHash("token-1"), "device-1", "Test Device", NOW.plusSeconds(3600), NOW);

        User user = userRepository.findByPublicId(publicId).orElseThrow();
        assertEquals(userId, user.id());
        assertEquals(UserStatus.ACTIVE, user.status());
        assertEquals("user1@example.com", piiEncryptor.decrypt(user.emailEncrypted()));
        assertEquals(piiHasher.hashEmail("user1@example.com"), user.emailHash());
        assertFalse(user.emailVerified());
        assertNull(user.phoneEncrypted());
        assertNull(user.phoneHash());

        LocalAccount localAccount = localAccountRepository.findByLoginId("user1").orElseThrow();
        assertEquals(localAccountId, localAccount.id());
        assertEquals(userId, localAccount.userId());
        assertEquals("hashed-password", localAccount.passwordHash());
        assertEquals(0, localAccount.failedLoginCount());
        assertNull(localAccount.lastFailedLoginAt());
        assertNull(localAccount.lockedUntil());

        OauthAccount oauthAccount = oauthAccountRepository
                .findByProviderAndProviderSubject(OauthProvider.GOOGLE, "google-subject-1")
                .orElseThrow();
        assertEquals(oauthAccountId, oauthAccount.id());
        assertEquals(userId, oauthAccount.userId());
        assertEquals(OauthProvider.GOOGLE, oauthAccount.provider());

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash("token-1")).orElseThrow();
        assertEquals(refreshTokenId, refreshToken.id());
        assertEquals(userId, refreshToken.userId());
        assertEquals("device-1", refreshToken.deviceId());
        assertEquals("Test Device", refreshToken.deviceName());
        assertNull(refreshToken.revokedAt());
        assertNull(refreshToken.lastUsedAt());
    }

    @Test
    @DisplayName("이메일 평문은 DB에 그대로 저장되지 않는다")
    void users_emailEncrypted_doesNotContainPlaintext() {
        insertUser("plaintext-check@example.com");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE position('plaintext-check' in encode(email_encrypted, 'escape')) > 0",
                Integer.class);
        assertEquals(0, count);
    }

    // =====================================================================
    // users 제약조건
    // =====================================================================

    @Test
    @DisplayName("활성 사용자끼리는 같은 이메일을 쓸 수 없다")
    void users_duplicateEmailHashAmongActive_violatesUniqueIndex() {
        insertUser("duplicate-email@example.com");

        assertThrows(DuplicateKeyException.class, () -> insertUser("duplicate-email@example.com"));
    }

    @Test
    @DisplayName("탈퇴한 사용자의 이메일은 다시 사용할 수 있다")
    void users_emailOfWithdrawnUser_canBeReused() {
        long withdrawnUserId = insertUser("reusable@example.com");
        jdbcTemplate.update(
                "UPDATE users SET status = 'WITHDRAWN', deleted_at = ? WHERE id = ?",
                Timestamp.from(NOW), withdrawnUserId);

        long newUserId = insertUser("reusable@example.com");

        assertTrue(newUserId > 0);
        assertEquals(2, countUsersWithEmail("reusable@example.com"));
    }

    @Test
    @DisplayName("대소문자만 다른 이메일은 같은 이메일로 취급해 중복을 막는다")
    void users_emailDifferingOnlyByCase_violatesUniqueIndex() {
        insertUser("Case@Example.com");

        assertThrows(DuplicateKeyException.class, () -> insertUser("case@example.com"));
    }

    @Test
    @DisplayName("활성 사용자끼리는 같은 전화번호를 쓸 수 없다")
    void users_duplicatePhoneHashAmongActive_violatesUniqueIndex() {
        long firstUserId = insertUser("phone1@example.com");
        setPhone(firstUserId, "+821012345678");
        long secondUserId = insertUser("phone2@example.com");

        assertThrows(DataIntegrityViolationException.class, () -> setPhone(secondUserId, "+821012345678"));
    }

    @Test
    @DisplayName("전화번호가 NULL이면 여러 사용자가 동시에 가질 수 있다")
    void users_phoneHashBothNull_succeeds() {
        insertUser("phone-null-1@example.com");
        insertUser("phone-null-2@example.com");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE phone_hash IS NULL", Integer.class);
        assertEquals(2, count);
    }

    @Test
    @DisplayName("public_id는 중복 저장할 수 없다")
    void users_duplicatePublicId_violatesUniqueConstraint() {
        UUID publicId = UUID.randomUUID();
        insertUser(publicId, "duplicate-public-id-1@example.com");

        assertThrows(DuplicateKeyException.class,
                () -> insertUser(publicId, "duplicate-public-id-2@example.com"));
    }

    @Test
    @DisplayName("정의되지 않은 users.status 값은 저장할 수 없다")
    void users_invalidStatus_violatesCheckConstraint() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertUserWithRawStatus("DELETED", "invalid-status@example.com"));
    }

    @Test
    @DisplayName("users.status에 더 이상 LOCKED를 저장할 수 없다")
    void users_lockedStatus_violatesCheckConstraint() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertUserWithRawStatus("LOCKED", "locked-status@example.com"));
    }

    @Test
    @DisplayName("users.status에 SUSPENDED를 저장할 수 있다")
    void users_suspendedStatus_succeeds() {
        long userId = userRepository.insert(
                UUID.randomUUID(), "suspended-user", UserStatus.SUSPENDED,
                piiEncryptor.encrypt("suspended@example.com"), piiHasher.hashEmail("suspended@example.com"), NOW);

        assertEquals(UserStatus.SUSPENDED, userRepository.findById(userId).orElseThrow().status());
    }

    @Test
    @DisplayName("email_encrypted 없이 사용자를 저장할 수 없다")
    void users_missingEmailEncrypted_violatesNotNullConstraint() {
        assertThrows(DataIntegrityViolationException.class, () -> userRepository.insert(
                UUID.randomUUID(), "no-email-user", UserStatus.ACTIVE, null, null, NOW));
    }

    // =====================================================================
    // 인증 테이블 제약조건
    // =====================================================================

    @Test
    @DisplayName("사용자 1명에 자체 로그인 계정은 하나만 연결할 수 있다")
    void localAccount_secondForSameUser_violatesUniqueConstraint() {
        long userId = insertUser("user2@example.com");
        localAccountRepository.insert(userId, "first-login", "hash-1", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> localAccountRepository.insert(userId, "second-login", "hash-2", NOW));
    }

    @Test
    @DisplayName("login_id는 중복 저장할 수 없다")
    void localAccount_duplicateLoginId_violatesUniqueConstraint() {
        long firstUserId = insertUser("user6@example.com");
        long secondUserId = insertUser("user7@example.com");
        localAccountRepository.insert(firstUserId, "duplicate-id", "hash-1", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> localAccountRepository.insert(secondUserId, "duplicate-id", "hash-2", NOW));

        assertFalse(localAccountRepository.existsByLoginId("duplicate-id-2"));
    }

    @Test
    @DisplayName("failed_login_count는 음수로 저장할 수 없다")
    void localAccount_negativeFailedLoginCount_violatesCheckConstraint() {
        long userId = insertUser("user14@example.com");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO local_accounts
                    (user_id, login_id, password_hash, failed_login_count, created_at, updated_at)
                VALUES (?, 'negative-count', 'hash', -1, ?, ?)
                """,
                userId, Timestamp.from(NOW), Timestamp.from(NOW)));
    }

    @Test
    @DisplayName("local_accounts에는 password_changed_at·last_login_at 컬럼이 없다")
    void localAccount_columns_noLongerIncludePasswordChangedAtOrLastLoginAt() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'local_accounts'
                    AND column_name IN ('password_changed_at', 'last_login_at')
                """,
                Integer.class);
        assertEquals(0, count);
    }

    @Test
    @DisplayName("존재하지 않는 user_id는 외래키 제약을 위반한다")
    void localAccount_nonExistentUserId_violatesForeignKeyConstraint() {
        assertThrows(DataIntegrityViolationException.class,
                () -> localAccountRepository.insert(999_999L, "orphan-login", "hash", NOW));
    }

    @Test
    @DisplayName("동일 provider와 provider_subject 조합은 중복 저장할 수 없다")
    void oauthAccount_duplicateProviderAndSubject_violatesUniqueConstraint() {
        long firstUserId = insertUser("user8@example.com");
        long secondUserId = insertUser("user9@example.com");
        oauthAccountRepository.insert(firstUserId, OauthProvider.GOOGLE, "shared-subject", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> oauthAccountRepository.insert(secondUserId, OauthProvider.GOOGLE, "shared-subject", NOW));
    }

    @Test
    @DisplayName("한 사용자가 같은 provider로 OAuth 계정을 두 번 연결할 수 없다")
    void oauthAccount_sameUserSameProviderTwice_violatesUniqueConstraint() {
        long userId = insertUser("user10@example.com");
        oauthAccountRepository.insert(userId, OauthProvider.GOOGLE, "subject-1", NOW);

        assertThrows(DuplicateKeyException.class,
                () -> oauthAccountRepository.insert(userId, OauthProvider.GOOGLE, "subject-2", NOW));
    }

    @Test
    @DisplayName("oauth_accounts.provider는 대문자 GOOGLE만 저장할 수 있다")
    void oauthAccount_lowercaseProvider_violatesCheckConstraint() {
        long userId = insertUser("user13@example.com");

        assertThrows(DataIntegrityViolationException.class, () -> insertOauthAccountWithRawProvider(userId, "google"));
        assertThrows(DataIntegrityViolationException.class, () -> insertOauthAccountWithRawProvider(userId, "KAKAO"));
    }

    @Test
    @DisplayName("token_hash는 중복 저장할 수 없다")
    void refreshToken_duplicateTokenHash_violatesUniqueConstraint() {
        long firstUserId = insertUser("user11@example.com");
        long secondUserId = insertUser("user12@example.com");
        refreshTokenRepository.insert(
                firstUserId, tokenHash("shared-token"), "device-1", "Device 1", NOW.plusSeconds(60), NOW);

        assertThrows(DuplicateKeyException.class,
                () -> refreshTokenRepository.insert(
                        secondUserId, tokenHash("shared-token"), "device-2", "Device 2", NOW.plusSeconds(60), NOW));
    }

    @Test
    @DisplayName("같은 기기에 활성 refresh token은 1개만 존재할 수 있다")
    void refreshToken_secondActiveTokenForSameDevice_violatesPartialUniqueIndex() {
        long userId = insertUser("device-unique@example.com");
        refreshTokenRepository.insert(userId, tokenHash("token-a"), "device-x", "Device X", NOW.plusSeconds(60), NOW);

        assertThrows(DuplicateKeyException.class, () -> refreshTokenRepository.insert(
                userId, tokenHash("token-b"), "device-x", "Device X", NOW.plusSeconds(60), NOW));
    }

    @Test
    @DisplayName("기존 토큰을 폐기하면 같은 기기로 다시 발급할 수 있다")
    void refreshToken_afterRevoke_sameDeviceCanIssueAgain() {
        long userId = insertUser("device-rotate@example.com");
        refreshTokenRepository.insert(userId, tokenHash("token-old"), "device-y", "Device Y", NOW.plusSeconds(60), NOW);
        jdbcTemplate.update("UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ?",
                Timestamp.from(NOW), tokenHash("token-old"));

        long newTokenId = refreshTokenRepository.insert(
                userId, tokenHash("token-new"), "device-y", "Device Y", NOW.plusSeconds(60), NOW);

        assertTrue(newTokenId > 0);
        // 폐기된 옛 token_hash가 남아 있어야 탈취 재사용 탐지가 가능하다.
        assertTrue(refreshTokenRepository.findByTokenHash(tokenHash("token-old")).isPresent());
    }

    @Test
    @DisplayName("사용자 1명은 서로 다른 기기로 리프레시 토큰을 여러 개 가질 수 있다")
    void refreshToken_differentDevices_bothSucceed() {
        long userId = insertUser("user5@example.com");

        refreshTokenRepository.insert(userId, tokenHash("token-a"), "device-a", "Device A", NOW.plusSeconds(60), NOW);
        refreshTokenRepository.insert(userId, tokenHash("token-b"), "device-b", "Device B", NOW.plusSeconds(120), NOW);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
        assertEquals(2, count);
    }

    @Test
    @DisplayName("만료시각은 생성시각보다 이후여야 한다")
    void refreshToken_expiresAtNotAfterCreatedAt_violatesCheckConstraint() {
        long userId = insertUser("user15@example.com");

        assertThrows(DataIntegrityViolationException.class,
                () -> refreshTokenRepository.insert(
                        userId, tokenHash("same-instant"), "device-1", "Device 1", NOW, NOW));
    }

    @Test
    @DisplayName("token_hash는 64자를 초과하면 저장할 수 없다")
    void refreshToken_tokenHashTooLong_violatesLengthConstraint() {
        long userId = insertUser("user17@example.com");

        assertThrows(DataIntegrityViolationException.class,
                () -> refreshTokenRepository.insert(
                        userId, "a".repeat(65), "device-1", "Device 1", NOW.plusSeconds(60), NOW));
    }

    // =====================================================================
    // 신규 도메인 테이블 제약조건
    // =====================================================================

    @Test
    @DisplayName("사용자당 ACTIVE 장바구니는 1건만 존재할 수 있다")
    void carts_secondActiveCartForSameUser_violatesPartialUniqueIndex() {
        long userId = insertUser("cart@example.com");
        insertCart(userId, "ACTIVE");

        assertThrows(DuplicateKeyException.class, () -> insertCart(userId, "ACTIVE"));
    }

    @Test
    @DisplayName("ACTIVE가 아닌 장바구니는 사용자당 여러 건 존재할 수 있다")
    void carts_multipleNonActiveCarts_succeed() {
        long userId = insertUser("cart-expired@example.com");
        insertCart(userId, "EXPIRED");
        insertCart(userId, "EXPIRED");
        insertCart(userId, "ACTIVE");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?", Integer.class, userId);
        assertEquals(3, count);
    }

    @Test
    @DisplayName("같은 장바구니에 동일 상품·옵션을 중복해 담을 수 없다")
    void cartItems_duplicateProductAndVariant_violatesUniqueConstraint() {
        long userId = insertUser("cart-item@example.com");
        long cartId = insertCart(userId, "ACTIVE");
        long merchantId = insertMerchant("merchant-1");
        insertCartItem(cartId, merchantId, "product-1", "variant-1");

        assertThrows(DuplicateKeyException.class,
                () -> insertCartItem(cartId, merchantId, "product-1", "variant-1"));
    }

    @Test
    @DisplayName("사용자당 활성 기본 배송지는 1건만 존재할 수 있다")
    void addresses_secondDefaultForSameUser_violatesPartialUniqueIndex() {
        long userId = insertUser("address@example.com");
        insertAddress(userId, true);

        assertThrows(DuplicateKeyException.class, () -> insertAddress(userId, true));
    }

    @Test
    @DisplayName("기본 배송지를 삭제하면 다시 기본 배송지를 지정할 수 있다")
    void addresses_afterSoftDelete_newDefaultIsAllowed() {
        long userId = insertUser("address-delete@example.com");
        long firstAddressId = insertAddress(userId, true);
        jdbcTemplate.update("UPDATE addresses SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), firstAddressId);

        assertTrue(insertAddress(userId, true) > 0);
    }

    @Test
    @DisplayName("기본이 아닌 배송지는 사용자당 여러 건 존재할 수 있다")
    void addresses_multipleNonDefault_succeed() {
        long userId = insertUser("address-multi@example.com");
        insertAddress(userId, false);
        insertAddress(userId, false);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM addresses WHERE user_id = ?", Integer.class, userId);
        assertEquals(2, count);
    }

    @Test
    @DisplayName("사용자당 Agent 앱당 활성 연동은 1건만 존재할 수 있다")
    void agentConnections_secondActiveConnection_violatesPartialUniqueIndex() {
        long userId = insertUser("agent@example.com");
        long clientId = insertOauthClient("client-1");
        insertAgentConnection(userId, clientId);

        assertThrows(DuplicateKeyException.class, () -> insertAgentConnection(userId, clientId));
    }

    @Test
    @DisplayName("연동을 해제하면 같은 Agent 앱을 다시 연동할 수 있다")
    void agentConnections_afterRevoke_canConnectAgain() {
        long userId = insertUser("agent-revoke@example.com");
        long clientId = insertOauthClient("client-2");
        long connectionId = insertAgentConnection(userId, clientId);
        jdbcTemplate.update("UPDATE agent_connections SET revoked_at = ? WHERE id = ?",
                Timestamp.from(NOW), connectionId);

        assertTrue(insertAgentConnection(userId, clientId) > 0);
    }

    @Test
    @DisplayName("판매자의 주문서 식별자는 판매자별로 유일하다")
    void merchantCheckouts_duplicateMerchantCheckoutId_violatesUniqueConstraint() {
        long userId = insertUser("checkout@example.com");
        long cartId = insertCart(userId, "ACTIVE");
        long merchantId = insertMerchant("merchant-checkout");
        long purchaseRequestId = insertPurchaseRequest(userId, cartId);
        insertMerchantCheckout(purchaseRequestId, merchantId, "merchant-checkout-1");

        assertThrows(DuplicateKeyException.class,
                () -> insertMerchantCheckout(purchaseRequestId, merchantId, "merchant-checkout-1"));
    }

    @Test
    @DisplayName("같은 범위의 멱등성 키는 중복 저장할 수 없다")
    void idempotencyRecords_duplicateScopeAndKey_violatesUniqueConstraint() {
        insertIdempotencyRecord("checkout", "key-1");

        assertThrows(DuplicateKeyException.class, () -> insertIdempotencyRecord("checkout", "key-1"));
        assertTrue(insertIdempotencyRecord("payment", "key-1") > 0, "범위가 다르면 같은 키를 쓸 수 있다");
    }

    @Test
    @DisplayName("이상거래 평가 점수는 0~100 범위만 저장할 수 있다")
    void fraudAssessments_behaviorScoreOutOfRange_violatesCheckConstraint() {
        long userId = insertUser("fraud@example.com");
        long cartId = insertCart(userId, "ACTIVE");
        long purchaseRequestId = insertPurchaseRequest(userId, cartId);

        assertTrue(insertFraudAssessment(purchaseRequestId, "50.00") > 0);
        assertThrows(DataIntegrityViolationException.class,
                () -> insertFraudAssessment(purchaseRequestId, "100.01"));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertFraudAssessment(purchaseRequestId, "-0.01"));
    }

    @Test
    @DisplayName("fcm_device_tokens의 platform은 ANDROID·IOS만 저장할 수 있다")
    void fcmDeviceTokens_invalidPlatform_violatesCheckConstraint() {
        long userId = insertUser("fcm@example.com");

        assertTrue(insertFcmDeviceToken(userId, "device-fcm-1", "token-1", "ANDROID") > 0);
        assertThrows(DataIntegrityViolationException.class,
                () -> insertFcmDeviceToken(userId, "device-fcm-2", "token-2", "WEB"));
    }

    @Test
    @DisplayName("주문 금액 컬럼은 음수를 저장할 수 없다")
    void checkoutSnapshots_negativeAmount_violatesCheckConstraint() {
        long userId = insertUser("snapshot@example.com");
        long cartId = insertCart(userId, "ACTIVE");
        long merchantId = insertMerchant("merchant-snapshot");
        long purchaseRequestId = insertPurchaseRequest(userId, cartId);
        long merchantCheckoutId = insertMerchantCheckout(purchaseRequestId, merchantId, "merchant-checkout-neg");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                """
                INSERT INTO checkout_snapshots
                    (public_id, merchant_checkout_id, checkout_digest, merchant_checkout_payload,
                     total_amount, currency, created_at)
                VALUES (?, ?, ?, '{}'::jsonb, -1, 'KRW', ?)
                """,
                UUID.randomUUID(), merchantCheckoutId, digest("neg"), Timestamp.from(NOW)));
    }

    // =====================================================================
    // 헬퍼
    // =====================================================================

    private long insertUser(String email) {
        return insertUser(UUID.randomUUID(), email);
    }

    private long insertUser(UUID publicId, String email) {
        return userRepository.insert(
                publicId, "test-user", UserStatus.ACTIVE,
                piiEncryptor.encrypt(PiiNormalizer.email(email)), piiHasher.hashEmail(email), NOW);
    }

    private void insertUserWithRawStatus(String status, String email) {
        jdbcTemplate.update(
                """
                INSERT INTO users (public_id, name, status, email_encrypted, email_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), "raw-status-user", status,
                piiEncryptor.encrypt(PiiNormalizer.email(email)).toByteArray(), piiHasher.hashEmail(email),
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void setPhone(long userId, String phone) {
        jdbcTemplate.update(
                "UPDATE users SET phone_encrypted = ?, phone_hash = ? WHERE id = ?",
                piiEncryptor.encrypt(PiiNormalizer.phone(phone)).toByteArray(),
                piiHasher.hashPhone(phone),
                userId);
    }

    private int countUsersWithEmail(String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email_hash = ?", Integer.class, piiHasher.hashEmail(email));
        assertNotNull(count);
        return count;
    }

    private void insertOauthAccountWithRawProvider(long userId, String provider) {
        jdbcTemplate.update(
                """
                INSERT INTO oauth_accounts (user_id, provider, provider_subject, created_at, updated_at)
                VALUES (?, ?, 'raw-subject', ?, ?)
                """,
                userId, provider, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private long insertMerchant(String name) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants
                    (merchant_name, ucp_profile_url, base_url, business_profile, active_capabilities,
                     status, created_at)
                VALUES (?, 'https://merchant.example.com/profile', 'https://merchant.example.com',
                        '{}'::jsonb, '[]'::jsonb, 'ACTIVE', ?)
                RETURNING id
                """,
                Long.class, name, Timestamp.from(NOW));
    }

    private long insertCart(long userId, String status) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO carts (public_id, user_id, status, created_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, UUID.randomUUID(), userId, status, Timestamp.from(NOW));
    }

    private long insertCartItem(long cartId, long merchantId, String productId, String variantId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO cart_items
                    (public_id, cart_id, merchant_id, merchant_product_id, merchant_variant_id,
                     product_name, product_url, selected_options, quantity, unit_price_amount, currency)
                VALUES (?, ?, ?, ?, ?, '상품', 'https://merchant.example.com/p/1', '{}'::jsonb, 1, 1000, 'KRW')
                RETURNING id
                """,
                Long.class, UUID.randomUUID(), cartId, merchantId, productId, variantId);
    }

    private long insertAddress(long userId, boolean isDefault) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO addresses
                    (public_id, user_id, recipient_name_encrypted, recipient_phone_encrypted,
                     postal_code_encrypted, address_line1_encrypted, is_default, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, UUID.randomUUID(), userId,
                piiEncryptor.encrypt("수령인").toByteArray(),
                piiEncryptor.encrypt("+821012345678").toByteArray(),
                piiEncryptor.encrypt("06234").toByteArray(),
                piiEncryptor.encrypt("서울특별시 강남구 테헤란로 1").toByteArray(),
                isDefault, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private long insertOauthClient(String clientId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO oauth_clients
                    (client_id, client_name, client_type, redirect_uris, allowed_scopes, created_at, updated_at)
                VALUES (?, 'Test Agent', 'PUBLIC', '["https://agent.example.com/callback"]'::jsonb,
                        '["cart:read"]'::jsonb, ?, ?)
                RETURNING id
                """,
                Long.class, clientId, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private long insertAgentConnection(long userId, long oauthClientId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO agent_connections
                    (public_id, user_id, oauth_client_id, granted_scopes, created_at, updated_at)
                VALUES (?, ?, ?, '["cart:read"]'::jsonb, ?, ?)
                RETURNING id
                """,
                Long.class, UUID.randomUUID(), userId, oauthClientId, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private long insertPurchaseRequest(long userId, long cartId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO purchase_requests
                    (public_id, user_id, cart_id, request_channel, status, policy_version, created_at)
                VALUES (?, ?, ?, 'APP', 'AWAITING_APPROVAL', 'v1', ?)
                RETURNING id
                """,
                Long.class, UUID.randomUUID(), userId, cartId, Timestamp.from(NOW));
    }

    private long insertMerchantCheckout(long purchaseRequestId, long merchantId, String merchantCheckoutId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO merchant_checkouts
                    (purchase_request_id, merchant_id, merchant_checkout_id, checkout_digest, status, created_at)
                VALUES (?, ?, ?, ?, 'CREATING', ?)
                RETURNING id
                """,
                Long.class, purchaseRequestId, merchantId, merchantCheckoutId,
                digest(merchantCheckoutId), Timestamp.from(NOW));
    }

    private long insertIdempotencyRecord(String scope, String key) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO idempotency_records
                    (scope, idempotency_key, request_hash, processing_status, resource_type, resource_id,
                     response_status, response_payload, created_at)
                VALUES (?, ?, ?, 'COMPLETED', 'ORDER', 'resource-1', 200, '{}'::jsonb, ?)
                RETURNING id
                """,
                Long.class, scope, key, digest(scope + key), Timestamp.from(NOW));
    }

    private long insertFraudAssessment(long purchaseRequestId, String behaviorScore) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO fraud_assessments
                    (purchase_request_id, assessment_stage, decision, risk_level, behavior_score,
                     evaluation_status, result_payload, evaluation_snapshot_refs, assessed_at, created_at)
                VALUES (?, 'PRE_APPROVAL', 'ALLOW', 'LOW', CAST(? AS NUMERIC(5, 2)),
                        'SUCCESS', '{}'::jsonb, '[]'::jsonb, ?, ?)
                RETURNING id
                """,
                Long.class, purchaseRequestId, behaviorScore, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private long insertFcmDeviceToken(long userId, String deviceId, String fcmToken, String platform) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO fcm_device_tokens
                    (user_id, device_id, fcm_token, platform, last_seen_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, userId, deviceId, fcmToken, platform,
                Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject(
                """
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """,
                String.class, table, column);
    }

    private boolean constraintExists(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?", Integer.class, name);
        return count != null && count > 0;
    }

    private boolean indexExists(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?", Integer.class, name);
        return count != null && count > 0;
    }

    private static String tokenHash(String seed) {
        return (seed + "0".repeat(64)).substring(0, 64);
    }

    /** 주문서 검증 해시 자리를 채우는 더미 값. CHAR(64) 길이만 맞으면 된다. */
    private static String digest(String seed) {
        return tokenHash(seed);
    }
}
