package com.copenhagen.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.common.idempotency.IdempotencyScope;
import com.copenhagen.pay.common.idempotency.JdbcIdempotencyRepository;
import com.copenhagen.pay.common.idempotency.PayIdempotencyService;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionLifecycleService;
import com.copenhagen.pay.payment.authorization.JdbcAuthorizationSessionRepository;
import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import com.copenhagen.pay.payment.credential.mandate.MandateProperties;
import com.copenhagen.pay.payment.payment.JdbcPaymentExecutionRepository;
import com.copenhagen.pay.payment.payment.JdbcPaymentRepository;
import com.copenhagen.pay.payment.payment.PaymentExecutionCommand;
import com.copenhagen.pay.payment.payment.PaymentExecutionPreparationService;
import com.copenhagen.pay.payment.payment.PaymentLifecycleService;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayConcurrencyPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String MERCHANT_ID = "merchant-a";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000003";
    private static final String PAYMENT_METHOD_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000004";
    private static final String SESSION_ITEM_ID = "00000000-0000-0000-0000-000000000104";
    private static final String PURCHASE_REQUEST_ID = "00000000-0000-0000-0000-000000000005";
    private static final String CHECKOUT_MANDATE_ID = "00000000-0000-0000-0000-000000000006";
    private static final String PAYMENT_MANDATE_ID = "00000000-0000-0000-0000-000000000007";
    private static final String CREDENTIAL_ID = "00000000-0000-0000-0000-000000000008";
    private static final String PAYMENT_ID = "00000000-0000-0000-0000-000000000009";
    private static final String ATTEMPT_ID = "00000000-0000-0000-0000-000000000010";
    private static final String PAYMENT_IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440501";
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String DIGEST = "b".repeat(64);
    private static final String ENCRYPTION_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcAuthorizationSessionRepository sessionRepository;

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
    void setUp() {
        String schema = "pay_concurrency_" + UUID.randomUUID().toString().replace("-", "");
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
        insertMerchantAndPaymentMethod();
        sessionRepository = new JdbcAuthorizationSessionRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void concurrentApprovalAndRejectionCommitOnlyOneTransition() throws Exception {
        insertSession(AuthorizationSessionStatus.PENDING, true);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> approval = executor.submit(() -> {
                start.await();
                return sessionRepository.approve(SESSION_ID, USER_ID, "PIN", NOW);
            });
            Future<Boolean> rejection = executor.submit(() -> {
                start.await();
                return sessionRepository.reject(SESSION_ID, USER_ID, NOW);
            });
            start.countDown();

            boolean approved = approval.get(10, TimeUnit.SECONDS);
            boolean rejected = rejection.get(10, TimeUnit.SECONDS);

            assertTrue(approved ^ rejected);
            assertEquals(approved ? "APPROVED" : "REJECTED", status("authorization_sessions"));
            assertEquals(1, version("authorization_sessions"));
        }
    }

    @Test
    void paymentPreparationRollsBackStatusAndLedgerRowsWhenInsertFails() {
        insertPaymentFixtures();
        jdbc.update(
                """
                INSERT INTO payment_attempts
                    (id, payment_id, attempt_no, operation, request_hash,
                     pg_request_id, status, started_at)
                VALUES (?, ?, 1, 'CHARGE', ?, 'pg-existing-request', 'PROCESSING', ?)
                """,
                ATTEMPT_ID,
                PAYMENT_ID,
                REQUEST_HASH,
                java.sql.Timestamp.from(NOW));

        try (AnnotationConfigApplicationContext context = createPaymentContext()) {
            PaymentExecutionPreparationService service = context.getBean(
                    PaymentExecutionPreparationService.class);

            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> service.prepare(new PaymentExecutionCommand(
                            PAYMENT_ID,
                            PAYMENT_IDEMPOTENCY_KEY,
                            REQUEST_HASH)));
        }

        assertEquals("READY", status("payments"));
        assertEquals(0, version("payments"));
        assertEquals(1, count("payment_attempts"));
        assertEquals(0, count("pg_transactions"));
    }

    @Test
    void idempotencyResultSurvivesRepositoryRecreationAndKeepsResponseEncrypted() throws Exception {
        IdempotencyScope scope = idempotencyScope("durable-key");
        String responseBody = "{\"paymentId\":\"payment-secret\",\"status\":\"SUCCESS\"}";
        PayIdempotencyService firstService = newIdempotencyService();

        assertTrue(firstService.acquire(scope, REQUEST_HASH).owner());
        firstService.complete(
                scope,
                201,
                "application/json",
                responseBody.getBytes(StandardCharsets.UTF_8));

        PayIdempotencyService restartedService = newIdempotencyService();
        PayIdempotencyService.AcquireResult replay = restartedService.acquire(scope, REQUEST_HASH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        restartedService.replay(response, replay.record());

        assertFalse(replay.owner());
        assertEquals(201, response.getStatus());
        assertEquals(responseBody, response.getContentAsString());
        String encrypted = jdbc.queryForObject(
                "SELECT response_body_encrypted FROM pay_idempotency_records",
                String.class);
        assertFalse(encrypted.contains("payment-secret"));
        assertEquals(1, count("pay_idempotency_records"));

        PayException conflict = assertThrows(
                PayException.class,
                () -> restartedService.acquire(scope, "c".repeat(64)));
        assertEquals(PayErrorCode.IDEMPOTENCY_CONFLICT, conflict.errorCode());
    }

    @Test
    void concurrentDatabaseIdempotencyClaimsAllowOneOwnerAndOneReplay() throws Exception {
        IdempotencyScope scope = idempotencyScope("concurrent-key");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger owners = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(
                    () -> acquireAndComplete(scope, start, owners, replays));
            Future<Boolean> second = executor.submit(
                    () -> acquireAndComplete(scope, start, owners, replays));
            start.countDown();

            assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
        }

        assertEquals(1, owners.get());
        assertEquals(1, replays.get());
        assertEquals(1, count("pay_idempotency_records"));
    }

    private boolean acquireAndComplete(
            IdempotencyScope scope,
            CountDownLatch start,
            AtomicInteger owners,
            AtomicInteger replays) throws Exception {
        start.await();
        PayIdempotencyService service = newIdempotencyService();
        PayIdempotencyService.AcquireResult result = service.acquire(scope, REQUEST_HASH);
        if (result.owner()) {
            owners.incrementAndGet();
            service.complete(
                    scope,
                    200,
                    "application/json",
                    "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8));
        } else {
            replays.incrementAndGet();
        }
        return result.owner();
    }

    private PayIdempotencyService newIdempotencyService() {
        return new PayIdempotencyService(
                new JdbcIdempotencyRepository(jdbc),
                new IdempotencyGuard(),
                new ArtifactEncryptionService(
                        new MandateProperties("pay-service", "test-key", "", ENCRYPTION_KEY)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private IdempotencyScope idempotencyScope(String key) {
        return new IdempotencyScope(
                "MERCHANT",
                MERCHANT_ID,
                "MERCHANT",
                "POST",
                "/merchant/v1/payments",
                key);
    }

    private AnnotationConfigApplicationContext createPaymentContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        context.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));
        context.registerBean(IdempotencyGuard.class);
        context.register(
                JdbcPaymentExecutionRepository.class,
                JdbcPaymentRepository.class,
                JdbcAuthorizationSessionRepository.class,
                AuthorizationSessionLifecycleService.class,
                PaymentLifecycleService.class,
                PaymentExecutionPreparationService.class);
        context.refresh();
        return context;
    }

    private void insertMerchantAndPaymentMethod() {
        jdbc.update(
                """
                INSERT INTO merchant_accounts (id, merchant_id, display_name, auth_type, status)
                VALUES (?, ?, 'Merchant A', 'TOKEN', 'ACTIVE')
                ON CONFLICT (merchant_id) DO UPDATE SET
                    id = EXCLUDED.id, display_name = EXCLUDED.display_name,
                    auth_type = EXCLUDED.auth_type, status = EXCLUDED.status
                """,
                MERCHANT_ACCOUNT_ID,
                MERCHANT_ID);
        jdbc.update(
                """
                INSERT INTO payment_methods
                    (id, user_id, provider, provider_token_ref, brand, last4,
                     expiry_month, expiry_year, status, is_default)
                VALUES (?, ?, 'PG_MOCK', 'provider-token-ref', 'VISA', '1234',
                        12, 2030, 'ACTIVE', TRUE)
                """,
                PAYMENT_METHOD_ID,
                USER_ID);
    }

    private void insertSession(AuthorizationSessionStatus status, boolean selectedPaymentMethod) {
        jdbc.update(
                """
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id, total_amount_minor, currency,
                     selected_payment_method_id, status,
                     idempotency_key, request_hash, required_action, expires_at,
                     approved_at, auth_method, created_at, updated_at, version)
                VALUES (?, ?, ?, 99000, 'KRW', ?, ?,
                        'approval-key', ?, 'PIN', ?, ?, ?, ?, ?, 0)
                """,
                SESSION_ID,
                PURCHASE_REQUEST_ID,
                USER_ID,
                selectedPaymentMethod ? PAYMENT_METHOD_ID : null,
                status.name(),
                REQUEST_HASH,
                java.sql.Timestamp.from(NOW.plusSeconds(300)),
                status == AuthorizationSessionStatus.APPROVED
                        ? java.sql.Timestamp.from(NOW)
                        : null,
                status == AuthorizationSessionStatus.APPROVED ? "PIN" : null,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbc.update(
                """
                INSERT INTO authorization_session_items
                    (id, authorization_session_id, merchant_account_id, external_checkout_id,
                     checkout_digest, display_snapshot, amount_minor, currency, status,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 'checkout-test', ?, '{}'::jsonb, 99000, 'KRW', 'PENDING',
                        ?, ?, 0)
                """,
                SESSION_ITEM_ID,
                SESSION_ID,
                MERCHANT_ACCOUNT_ID,
                DIGEST,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
    }

    private void insertPaymentFixtures() {
        insertSession(AuthorizationSessionStatus.APPROVED, true);
        jdbc.update(
                """
                INSERT INTO checkout_mandates
                    (id, authorization_session_id, session_item_id, jti, issuer, audience,
                     checkout_digest, signed_artifact_encrypted, artifact_hash,
                     status, issued_at, expires_at)
                VALUES (?, ?, ?, 'checkout-jti', 'pay-service', ?, ?, 'encrypted', ?,
                        'ISSUED', ?, ?)
                """,
                CHECKOUT_MANDATE_ID,
                SESSION_ID,
                SESSION_ITEM_ID,
                "merchant:" + MERCHANT_ID,
                DIGEST,
                "c".repeat(64),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(300)));
        jdbc.update(
                """
                INSERT INTO payment_mandates
                    (id, authorization_session_id, session_item_id, jti, transaction_id, merchant_id,
                     external_checkout_id, amount_minor, currency, payment_method_ref,
                     signed_artifact_encrypted, artifact_hash, status, issued_at, expires_at)
                VALUES (?, ?, ?, 'payment-jti', 'transaction-001', ?, 'checkout-test', 99000,
                        'KRW', 'provider-token-ref', 'encrypted', ?, 'ISSUED', ?, ?)
                """,
                PAYMENT_MANDATE_ID,
                SESSION_ID,
                SESSION_ITEM_ID,
                MERCHANT_ID,
                "d".repeat(64),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(300)));
        jdbc.update(
                """
                INSERT INTO payment_credential_tokens
                    (id, authorization_session_id, session_item_id, checkout_mandate_id, payment_mandate_id,
                     token_hash, token_ciphertext, merchant_id, external_checkout_id,
                     checkout_digest, amount_minor, currency, status, issued_at, expires_at,
                     consumed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'encrypted', ?, 'checkout-test', ?, 99000, 'KRW',
                        'CONSUMED', ?, ?, ?)
                """,
                CREDENTIAL_ID,
                SESSION_ID,
                SESSION_ITEM_ID,
                CHECKOUT_MANDATE_ID,
                PAYMENT_MANDATE_ID,
                "e".repeat(64),
                MERCHANT_ID,
                DIGEST,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(300)),
                java.sql.Timestamp.from(NOW));
        jdbc.update(
                """
                INSERT INTO payments
                    (id, merchant_account_id, external_checkout_id, purchase_request_id,
                     credential_token_id, amount_minor, currency, status,
                     idempotency_key, request_hash, created_at, updated_at, version)
                VALUES (?, ?, 'checkout-test', ?, ?, 99000, 'KRW', 'READY', ?, ?, ?, ?, 0)
                """,
                PAYMENT_ID,
                MERCHANT_ACCOUNT_ID,
                PURCHASE_REQUEST_ID,
                CREDENTIAL_ID,
                PAYMENT_IDEMPOTENCY_KEY,
                REQUEST_HASH,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
    }

    private String status(String table) {
        return jdbc.queryForObject("SELECT status FROM " + table, String.class);
    }

    private int version(String table) {
        return jdbc.queryForObject("SELECT version FROM " + table, Integer.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }
}
