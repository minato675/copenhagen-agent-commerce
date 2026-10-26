package com.copenhagen.pay.payment.credential;

import com.copenhagen.pay.payment.authorization.AuthorizationSessionFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.payment.authorization.ApprovalProperties;
import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.credential.biometric.BiometricChallengeService;
import com.copenhagen.pay.payment.credential.biometric.BiometricChallengeStatus;
import com.copenhagen.pay.payment.credential.biometric.JdbcBiometricChallengeRepository;
import com.copenhagen.pay.payment.credential.biometric.JdbcBiometricCredentialRepository;
import com.copenhagen.pay.payment.credential.pin.JdbcPaymentPinCredentialRepository;
import com.copenhagen.pay.payment.credential.pin.PaymentPinService;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialSecurityPostgresIntegrationTest {

    private static final Instant INITIAL_TIME = Instant.parse("2026-09-15T00:00:00Z");
    private static final String USER_ID = "00000000-0000-0000-0000-000000000003";
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PAYMENT_METHOD_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000004";
    private static final String PURCHASE_REQUEST_ID = "00000000-0000-0000-0000-000000000005";
    private static final String BIOMETRIC_CREDENTIAL_ID = "00000000-0000-0000-0000-000000000006";
    private static final String DEVICE_ID = "device-001";

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private MutableClock clock;
    private KeyPair keyPair;
    private AuthorizationSession session;
    private PaymentPinService pinService;
    private BiometricChallengeService biometricService;

    @BeforeAll
    void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1")
                .start();
    }

    @AfterAll
    void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String schema = "credential_security_"
                + UUID.randomUUID().toString().replace("-", "");
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:" + postgres.getPort()
                        + "/postgres?currentSchema=" + schema,
                "postgres",
                "postgres");
        flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        clock = new MutableClock(INITIAL_TIME);
        keyPair = KeyPairGenerator.getInstance("EC").generateKeyPair();
        insertReferenceData();
        context = createContext();
        pinService = context.getBean(PaymentPinService.class);
        biometricService = context.getBean(BiometricChallengeService.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void pinFailureAndLockStateSurviveExceptionTransaction() {
        pinService.register(USER_ID, "123456");

        for (int attempt = 1; attempt <= 4; attempt++) {
            PayException exception = assertThrows(
                    PayException.class,
                    () -> pinService.verify(USER_ID, "000000"));

            assertEquals(PayErrorCode.PIN_INVALID, exception.errorCode());
            assertEquals(attempt, failedAttempts());
            assertEquals("ACTIVE", credentialStatus());
        }

        PayException locked = assertThrows(
                PayException.class,
                () -> pinService.verify(USER_ID, "000000"));

        assertEquals(PayErrorCode.PIN_TEMPORARILY_LOCKED, locked.errorCode());
        assertEquals(5, failedAttempts());
        assertEquals("LOCKED", credentialStatus());
        assertNotNull(lockedUntil());

        PayException blockedCorrectPin = assertThrows(
                PayException.class,
                () -> pinService.verify(USER_ID, "123456"));
        assertEquals(PayErrorCode.PIN_TEMPORARILY_LOCKED, blockedCorrectPin.errorCode());
        assertEquals(5, failedAttempts());

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        pinService.verify(USER_ID, "123456");

        assertEquals(0, failedAttempts());
        assertEquals("ACTIVE", credentialStatus());
        assertEquals(null, lockedUntil());
    }

    @Test
    void biometricChallengeValueIsStoredAndConsumedByIdOnly() throws Exception {
        BiometricChallengeService.IssuedChallenge issued = biometricService.issue(
                USER_ID,
                session,
                DEVICE_ID);
        String storedValue = jdbc.queryForObject(
                "SELECT challenge_value FROM biometric_challenges WHERE id = ?",
                String.class,
                issued.challengeId());
        String storedHash = jdbc.queryForObject(
                "SELECT challenge_hash FROM biometric_challenges WHERE id = ?",
                String.class,
                issued.challengeId());

        assertEquals(issued.challenge(), storedValue);
        assertNotEquals(issued.challenge(), storedHash);

        String assertion = sign(issued.challenge());
        assertEquals(
                BiometricChallengeService.VerificationResult.SUCCESS,
                biometricService.verify(USER_ID, session.id(), issued.challengeId(), assertion));
        assertEquals(
                BiometricChallengeStatus.SUCCESS.name(),
                challengeStatus(issued.challengeId()));

        PayException replay = assertThrows(
                PayException.class,
                () -> biometricService.verify(
                        USER_ID,
                        session.id(),
                        issued.challengeId(),
                        assertion));
        assertEquals(PayErrorCode.BIOMETRIC_CHALLENGE_REPLAYED, replay.errorCode());
    }

    @Test
    void biometricChallengeExpiresAndCannotBeUsedFromAnotherSession() throws Exception {
        BiometricChallengeService.IssuedChallenge issued = biometricService.issue(
                USER_ID,
                session,
                DEVICE_ID);
        String assertion = sign(issued.challenge());

        PayException wrongSession = assertThrows(
                PayException.class,
                () -> biometricService.verify(
                        USER_ID,
                        "00000000-0000-0000-0000-000000000007",
                        issued.challengeId(),
                        assertion));
        assertEquals(PayErrorCode.BIOMETRIC_CHALLENGE_INVALID, wrongSession.errorCode());
        assertEquals(
                BiometricChallengeStatus.PENDING.name(),
                challengeStatus(issued.challengeId()));

        clock.advance(Duration.ofSeconds(61));
        assertEquals(
                BiometricChallengeService.VerificationResult.FALLBACK_TO_PIN,
                biometricService.verify(USER_ID, session.id(), issued.challengeId(), assertion));
        assertEquals(
                BiometricChallengeStatus.EXPIRED.name(),
                challengeStatus(issued.challengeId()));
    }

    private AnnotationConfigApplicationContext createContext() {
        AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext();
        created.register(TransactionConfiguration.class);
        created.registerBean(DataSource.class, () -> dataSource);
        created.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        created.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        created.registerBean(Clock.class, () -> clock);
        created.registerBean(PasswordEncoder.class, NoOpPasswordEncoder::getInstance);
        created.registerBean(IdempotencyGuard.class);
        created.registerBean(ApprovalProperties.class, () -> new ApprovalProperties(300, 60, true));
        created.register(
                JdbcPaymentPinCredentialRepository.class,
                com.copenhagen.pay.payment.credential.pin.PaymentPinFailureService.class,
                PaymentPinService.class,
                JdbcBiometricCredentialRepository.class,
                JdbcBiometricChallengeRepository.class,
                BiometricChallengeService.class);
        created.refresh();
        return created;
    }

    private void insertReferenceData() {
        jdbc.update(
                """
                INSERT INTO merchant_accounts (id, merchant_id, display_name, auth_type, status)
                VALUES (?, 'merchant-test', 'Test Merchant', 'TOKEN', 'ACTIVE')
                """,
                MERCHANT_ACCOUNT_ID);
        jdbc.update(
                """
                INSERT INTO payment_methods
                    (id, user_id, provider, provider_token_ref, status, is_default)
                VALUES (?, ?, 'TEST', 'token-ref', 'ACTIVE', TRUE)
                """,
                PAYMENT_METHOD_ID,
                USER_ID);
        jdbc.update(
                """
                INSERT INTO biometric_credentials
                    (id, user_id, device_id, public_key, status, registered_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """,
                BIOMETRIC_CREDENTIAL_ID,
                USER_ID,
                DEVICE_ID,
                publicKey(),
                java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update(
                """
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id,
                     total_amount_minor, currency, selected_payment_method_id, status,
                     idempotency_key, request_hash, required_action, expires_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 99000, 'KRW', ?,
                        'PENDING', 'approval-test', ?, 'BIOMETRIC', ?, ?, ?, 0)
                """,
                SESSION_ID,
                PURCHASE_REQUEST_ID,
                USER_ID,
                PAYMENT_METHOD_ID,
                "a".repeat(64),
                java.sql.Timestamp.from(INITIAL_TIME.plusSeconds(300)),
                java.sql.Timestamp.from(INITIAL_TIME),
                java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update(
                """
                INSERT INTO authorization_session_items
                    (id, authorization_session_id, merchant_account_id, external_checkout_id,
                     checkout_digest, display_snapshot, amount_minor, currency, status,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 'checkout-test', ?, '{}'::jsonb, 99000, 'KRW', 'PENDING',
                        ?, ?, 0)
                """,
                java.util.UUID.randomUUID().toString(),
                SESSION_ID,
                MERCHANT_ACCOUNT_ID,
                "0".repeat(64),
                java.sql.Timestamp.from(INITIAL_TIME),
                java.sql.Timestamp.from(INITIAL_TIME));
        session = AuthorizationSessionFixtures.singleMerchant(
                SESSION_ID,
                PURCHASE_REQUEST_ID,
                USER_ID,
                MERCHANT_ACCOUNT_ID,
                "merchant-test",
                "Test Merchant",
                "checkout-test",
                "0".repeat(64),
                "{}",
                99000,
                "KRW",
                PAYMENT_METHOD_ID,
                AuthorizationSessionStatus.PENDING,
                "approval-test",
                "a".repeat(64),
                "BIOMETRIC",
                INITIAL_TIME.plusSeconds(300),
                null,
                null,
                INITIAL_TIME,
                INITIAL_TIME,
                0);
    }

    private String publicKey() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(keyPair.getPublic().getEncoded());
    }

    private String sign(String challenge) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(challenge.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private int failedAttempts() {
        return jdbc.queryForObject(
                "SELECT failed_attempts FROM payment_pin_credentials WHERE user_id = ?",
                Integer.class,
                USER_ID);
    }

    private String credentialStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM payment_pin_credentials WHERE user_id = ?",
                String.class,
                USER_ID);
    }

    private java.sql.Timestamp lockedUntil() {
        return jdbc.queryForObject(
                "SELECT locked_until FROM payment_pin_credentials WHERE user_id = ?",
                java.sql.Timestamp.class,
                USER_ID);
    }

    private String challengeStatus(String challengeId) {
        return jdbc.queryForObject(
                "SELECT status FROM biometric_challenges WHERE id = ?",
                String.class,
                challengeId);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
