package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.payment.credential.biometric.BiometricChallengeService;
import com.copenhagen.pay.payment.credential.biometric.JdbcBiometricChallengeRepository;
import com.copenhagen.pay.payment.credential.biometric.JdbcBiometricCredentialRepository;
import com.copenhagen.pay.payment.credential.mandate.IssuedPaymentCredential;
import com.copenhagen.pay.payment.credential.mandate.PaymentCredentialIssuer;
import com.copenhagen.pay.payment.credential.pin.JdbcPaymentPinCredentialRepository;
import com.copenhagen.pay.payment.credential.pin.PaymentPinService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 생체 미등록 사용자의 PIN 폴백. 예전에는 issue()가 던진 예외가 같은 트랜잭션을
 * rollback-only로 표시해, 폴백을 마쳐도 커밋에서 UnexpectedRollbackException이 났다.
 * 트랜잭션 프록시가 걸린 실제 빈으로 돌려야 재현되므로 postgres 통합 테스트로 둔다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrepareDetailPinFallbackPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String USER_ID = "00000000-0000-0000-0000-000000000003";
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PAYMENT_METHOD_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000004";
    private static final String DEVICE_ID = "device-001";

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private AuthorizationApprovalService approvalService;
    private AuthorizationSessionStore sessionStore;
    private KeyPair keyPair;

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
        String schema = "prepare_detail_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = new DriverManagerDataSource(
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
        keyPair = KeyPairGenerator.getInstance("EC").generateKeyPair();
        insertReferenceData();
        context = createContext(dataSource);
        approvalService = context.getBean(AuthorizationApprovalService.class);
        sessionStore = context.getBean(AuthorizationSessionStore.class);
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
    void biometricNotRegisteredFallsBackToPinWithoutRollback() {
        AuthorizationSession session = session();

        AuthorizationApprovalService.PreparedDetail prepared =
                approvalService.prepareDetail(session, DEVICE_ID);

        assertTrue(prepared.challenge().isEmpty());
        assertEquals("PIN", prepared.session().requiredAction());
        assertEquals("PIN", requiredAction());
    }

    @Test
    void biometricRegisteredStillIssuesChallenge() {
        insertBiometricCredential();
        AuthorizationSession session = session();

        AuthorizationApprovalService.PreparedDetail prepared =
                approvalService.prepareDetail(session, DEVICE_ID);

        assertTrue(prepared.challenge().isPresent());
        assertEquals("BIOMETRIC", requiredAction());
    }

    private AuthorizationSession session() {
        return sessionStore.findByIdAndUserId(SESSION_ID, USER_ID).orElseThrow();
    }

    private String requiredAction() {
        return jdbc.queryForObject(
                "SELECT required_action FROM authorization_sessions WHERE id = ?",
                String.class,
                SESSION_ID);
    }

    private AnnotationConfigApplicationContext createContext(DataSource dataSource) {
        AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext();
        created.register(TransactionConfiguration.class);
        created.registerBean(DataSource.class, () -> dataSource);
        created.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        created.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        created.registerBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));
        created.registerBean(ApprovalProperties.class, () -> new ApprovalProperties(300, 60, true));
        created.registerBean(
                PaymentCredentialIssuer.class,
                () -> (session, item, authMethod) ->
                        new IssuedPaymentCredential(null, session.expiresAt(), null));
        // prepareDetail 경로에서는 쓰이지 않지만 생성자 의존이라 채워 둔다.
        created.registerBean(
                PaymentPinService.class,
                () -> new PaymentPinService(
                        new JdbcPaymentPinCredentialRepository(new JdbcTemplate(dataSource)),
                        NoOpPasswordEncoder.getInstance(),
                        Clock.fixed(NOW, ZoneOffset.UTC)));
        created.register(
                JdbcBiometricCredentialRepository.class,
                JdbcBiometricChallengeRepository.class,
                BiometricChallengeService.class,
                JdbcAuthorizationSessionRepository.class,
                AuthorizationApprovalService.class);
        created.refresh();
        return created;
    }

    private void insertBiometricCredential() {
        jdbc.update(
                """
                INSERT INTO biometric_credentials
                    (id, user_id, device_id, public_key, status, registered_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """,
                UUID.randomUUID().toString(),
                USER_ID,
                DEVICE_ID,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                Timestamp.from(NOW));
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
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id,
                     total_amount_minor, currency, selected_payment_method_id, status,
                     idempotency_key, request_hash, required_action, expires_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 99000, 'KRW', ?,
                        'PENDING', 'approval-test', ?, 'BIOMETRIC', ?, ?, ?, 0)
                """,
                SESSION_ID,
                UUID.randomUUID().toString(),
                USER_ID,
                PAYMENT_METHOD_ID,
                "a".repeat(64),
                Timestamp.from(NOW.plusSeconds(300)),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        jdbc.update(
                """
                INSERT INTO authorization_session_items
                    (id, authorization_session_id, merchant_account_id, external_checkout_id,
                     checkout_digest, display_snapshot, amount_minor, currency, status,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 'checkout-test', ?, '{}'::jsonb, 99000, 'KRW', 'PENDING',
                        ?, ?, 0)
                """,
                UUID.randomUUID().toString(),
                SESSION_ID,
                MERCHANT_ACCOUNT_ID,
                "0".repeat(64),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }
}
