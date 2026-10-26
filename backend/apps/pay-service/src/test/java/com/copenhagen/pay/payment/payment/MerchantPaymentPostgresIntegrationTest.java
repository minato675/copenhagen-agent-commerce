package com.copenhagen.pay.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.payment.authorization.CheckoutHashCodec;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionLifecycleService;
import com.copenhagen.pay.payment.authorization.JdbcAuthorizationSessionRepository;
import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import com.copenhagen.pay.payment.credential.mandate.JdbcPaymentCredentialConsumptionRepository;
import com.copenhagen.pay.payment.credential.mandate.MandateProperties;
import com.copenhagen.pay.payment.credential.mandate.PaymentCredentialConsumptionService;
import com.copenhagen.pay.payment.merchant.JdbcMerchantAccountRepository;
import com.copenhagen.pay.payment.pg.PaymentGateway;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeRequest;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantPaymentPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String MERCHANT_ID = "merchant-a";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000004";
    private static final String SESSION_ITEM_ID = "00000000-0000-0000-0000-000000000104";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000003";
    private static final String PAYMENT_METHOD_ID = "00000000-0000-0000-0000-000000000002";
    private static final String PURCHASE_REQUEST_ID = "00000000-0000-0000-0000-000000000005";
    private static final String CHECKOUT_MANDATE_ID = "00000000-0000-0000-0000-000000000006";
    private static final String PAYMENT_MANDATE_ID = "00000000-0000-0000-0000-000000000007";
    private static final String CREDENTIAL_ID = "00000000-0000-0000-0000-000000000008";
    private static final String CHECKOUT_ID = "checkout-123";
    private static final String DIGEST = "0".repeat(64);
    private static final long AMOUNT_MINOR = 87000;
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440202";
    private static final String REQUEST_HASH = "a".repeat(64);

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private RecordingPaymentGateway gateway;
    private MerchantPaymentService service;
    private MerchantPaymentQueryService queryService;

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
        String schema = "merchant_payment_" + UUID.randomUUID().toString().replace("-", "");
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
        insertFixtures();
        gateway = new RecordingPaymentGateway();
        context = createContext();
        service = context.getBean(MerchantPaymentService.class);
        queryService = context.getBean(MerchantPaymentQueryService.class);
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
    void successPersistsAllLedgersAndCompletesAuthorization() {
        gateway.outcome = ChargeResult.Outcome.SUCCESS;

        MerchantPaymentService.PaymentResult result = service.create(command());

        assertEquals(PaymentStatus.SUCCESS, result.status());
        assertEquals("pg-pg-charge-", result.execution().pgTransactionId().substring(0, 13));
        assertEquals("CONSUMED", status("payment_credential_tokens"));
        assertEquals("SUCCESS", status("payments"));
        assertEquals("SUCCESS", status("payment_attempts"));
        assertEquals("SUCCESS", status("pg_transactions"));
        // 결제 결과는 세션이 아니라 판매처별 item 이 받는다.
        assertEquals("APPROVED", status("authorization_sessions"));
        assertEquals("COMPLETED", status("authorization_session_items"));
        assertEquals(1, count("payment_receipts"));
        assertEquals(0, count("payment_reconciliation_jobs"));
        assertEquals(1, gateway.calls.get());
    }

    @Test
    void failedPaymentIsPersistedWithoutReceipt() {
        gateway.outcome = ChargeResult.Outcome.FAILED;

        MerchantPaymentService.PaymentResult result = service.create(command());

        assertEquals(PaymentStatus.FAILED, result.status());
        assertEquals("FAILED", status("payments"));
        assertEquals("FAILED", status("payment_attempts"));
        assertEquals("FAILED", status("pg_transactions"));
        assertEquals("APPROVED", status("authorization_sessions"));
        assertEquals("INVALIDATED", status("authorization_session_items"));
        assertEquals(0, count("payment_receipts"));
        assertEquals(0, count("payment_reconciliation_jobs"));
    }

    @Test
    void unknownPaymentCreatesReconciliationJobAndReplayDoesNotChargeAgain() {
        gateway.outcome = ChargeResult.Outcome.UNKNOWN;

        MerchantPaymentService.PaymentResult first = service.create(command());
        MerchantPaymentService.PaymentResult replay = service.create(command());

        assertEquals(PaymentStatus.UNKNOWN, first.status());
        assertEquals(PaymentStatus.UNKNOWN, replay.status());
        assertEquals(first.payment().id(), replay.payment().id());
        assertEquals("UNKNOWN", status("payments"));
        assertEquals("UNKNOWN", status("payment_attempts"));
        assertEquals("UNKNOWN", status("pg_transactions"));
        assertEquals("APPROVED", status("authorization_sessions"));
        assertEquals(1, count("payment_reconciliation_jobs"));
        assertEquals(0, count("payment_receipts"));
        assertEquals(1, gateway.calls.get());
    }

    @Test
    void merchantCanReadOwnedPaymentWithoutCallingPgOrChangingLedgers() {
        gateway.outcome = ChargeResult.Outcome.UNKNOWN;
        MerchantPaymentService.PaymentResult created = service.create(command());
        int gatewayCallsBeforeQuery = gateway.calls.get();
        String paymentStatusBeforeQuery = status("payments");
        int attemptsBeforeQuery = count("payment_attempts");
        int transactionsBeforeQuery = count("pg_transactions");
        int receiptsBeforeQuery = count("payment_receipts");
        int reconciliationJobsBeforeQuery = count("payment_reconciliation_jobs");
        String authorizationStatusBeforeQuery = status("authorization_sessions");

        MerchantPaymentQueryRepository.PaymentView payment = queryService.find(
                MERCHANT_ID,
                created.payment().id());

        assertEquals(created.payment().id(), payment.paymentId());
        assertEquals(PaymentStatus.UNKNOWN, payment.status());
        assertEquals(AMOUNT_MINOR, payment.amountMinor());
        assertEquals("KRW", payment.currency());
        assertEquals(gatewayCallsBeforeQuery, gateway.calls.get());
        assertEquals(paymentStatusBeforeQuery, status("payments"));
        assertEquals(attemptsBeforeQuery, count("payment_attempts"));
        assertEquals(transactionsBeforeQuery, count("pg_transactions"));
        assertEquals(receiptsBeforeQuery, count("payment_receipts"));
        assertEquals(reconciliationJobsBeforeQuery, count("payment_reconciliation_jobs"));
        assertEquals(authorizationStatusBeforeQuery, status("authorization_sessions"));
    }

    @Test
    void anotherMerchantCannotDiscoverPayment() {
        MerchantPaymentService.PaymentResult created = service.create(command());

        PayException exception = assertThrows(
                PayException.class,
                () -> queryService.find("merchant-other", created.payment().id()));

        assertEquals(PayErrorCode.RESOURCE_NOT_FOUND, exception.errorCode());
    }

    @Test
    void missingPaymentReturnsNotFound() {
        PayException exception = assertThrows(
                PayException.class,
                () -> queryService.find(
                        MERCHANT_ID,
                        "00000000-0000-0000-0000-000000000099"));

        assertEquals(PayErrorCode.RESOURCE_NOT_FOUND, exception.errorCode());
    }

    @Test
    void invalidCredentialScopeDoesNotCreatePaymentOrConsumeCredential() {
        MerchantPaymentService.CreateCommand invalid = new MerchantPaymentService.CreateCommand(
                MERCHANT_ID,
                CHECKOUT_ID,
                externalHash(),
                AMOUNT_MINOR + 1,
                "KRW",
                CREDENTIAL_ID,
                IDEMPOTENCY_KEY,
                REQUEST_HASH);

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(invalid));

        assertEquals(PayErrorCode.CREDENTIAL_SCOPE_MISMATCH, exception.errorCode());
        assertEquals(0, count("payments"));
        assertEquals("ISSUED", status("payment_credential_tokens"));
        assertEquals(0, gateway.calls.get());
    }

    @Test
    void concurrentSameRequestCreatesOnePaymentAndCallsGatewayOnce() throws Exception {
        gateway.outcome = ChargeResult.Outcome.SUCCESS;
        gateway.blockCharge = true;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MerchantPaymentService.PaymentResult> first = executor.submit(
                    () -> service.create(command()));
            assertTrue(gateway.chargeStarted.await(10, TimeUnit.SECONDS));
            Future<MerchantPaymentService.PaymentResult> second = executor.submit(
                    () -> service.create(command()));
            gateway.releaseCharge.countDown();

            MerchantPaymentService.PaymentResult firstResult = first.get(10, TimeUnit.SECONDS);
            MerchantPaymentService.PaymentResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertEquals(firstResult.payment().id(), secondResult.payment().id());
            assertEquals(PaymentStatus.SUCCESS, secondResult.status());
        }
        assertEquals(1, count("payments"));
        assertEquals(1, count("payment_attempts"));
        assertEquals(1, count("pg_transactions"));
        assertEquals(1, gateway.calls.get());
    }

    private AnnotationConfigApplicationContext createContext() {
        AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext();
        created.register(TransactionConfiguration.class);
        created.registerBean(DataSource.class, () -> dataSource);
        created.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        created.registerBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        created.registerBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));
        created.registerBean(PaymentGateway.class, () -> gateway);
        created.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        created.registerBean(ArtifactEncryptionService.class, () -> new ArtifactEncryptionService(
                new MandateProperties("pay-test", "test-key", "", "")));
        created.registerBean(IdempotencyGuard.class);
        created.register(
                JdbcMerchantAccountRepository.class,
                JdbcPaymentCredentialConsumptionRepository.class,
                JdbcMerchantPaymentQueryRepository.class,
                JdbcPaymentExecutionRepository.class,
                JdbcPaymentRepository.class,
                JdbcAuthorizationSessionRepository.class,
                AuthorizationSessionLifecycleService.class,
                PaymentCredentialConsumptionService.class,
                PaymentLifecycleService.class,
                PaymentReceiptService.class,
                PaymentExecutionPreparationService.class,
                PaymentExecutionSettlementService.class,
                PaymentExecutionService.class,
                MerchantPaymentService.class,
                MerchantPaymentQueryService.class);
        created.refresh();
        return created;
    }

    private void insertFixtures() {
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
        jdbc.update(
                """
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id,
                     total_amount_minor, currency, selected_payment_method_id, status,
                     idempotency_key, request_hash, required_action, expires_at,
                     approved_at, auth_method, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'KRW', ?, 'APPROVED',
                        'approval-key', ?, 'PIN', ?, ?, 'PIN', ?, ?, 1)
                """,
                SESSION_ID,
                PURCHASE_REQUEST_ID,
                USER_ID,
                AMOUNT_MINOR,
                PAYMENT_METHOD_ID,
                REQUEST_HASH,
                java.sql.Timestamp.from(NOW.plusSeconds(300)),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbc.update(
                """
                INSERT INTO authorization_session_items
                    (id, authorization_session_id, merchant_account_id, external_checkout_id,
                     checkout_digest, display_snapshot, amount_minor, currency, status,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, '{}'::jsonb, ?, 'KRW', 'PENDING', ?, ?, 0)
                """,
                SESSION_ITEM_ID,
                SESSION_ID,
                MERCHANT_ACCOUNT_ID,
                CHECKOUT_ID,
                DIGEST,
                AMOUNT_MINOR,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
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
                "b".repeat(64),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(300)));
        jdbc.update(
                """
                INSERT INTO payment_mandates
                    (id, authorization_session_id, session_item_id, jti, transaction_id, merchant_id,
                     external_checkout_id, amount_minor, currency, payment_method_ref,
                     signed_artifact_encrypted, artifact_hash, status, issued_at, expires_at)
                VALUES (?, ?, ?, 'payment-jti', 'transaction-001', ?, ?, ?, 'KRW',
                        'provider-token-ref', 'encrypted', ?, 'ISSUED', ?, ?)
                """,
                PAYMENT_MANDATE_ID,
                SESSION_ID,
                SESSION_ITEM_ID,
                MERCHANT_ID,
                CHECKOUT_ID,
                AMOUNT_MINOR,
                "c".repeat(64),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(300)));
        jdbc.update(
                """
                INSERT INTO payment_credential_tokens
                    (id, authorization_session_id, session_item_id, checkout_mandate_id,
                     payment_mandate_id,
                     token_hash, token_ciphertext, merchant_id, external_checkout_id,
                     checkout_digest, amount_minor, currency, status, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'encrypted', ?, ?, ?, ?, 'KRW', 'ISSUED', ?, ?)
                """,
                CREDENTIAL_ID,
                SESSION_ID,
                SESSION_ITEM_ID,
                CHECKOUT_MANDATE_ID,
                PAYMENT_MANDATE_ID,
                "d".repeat(64),
                MERCHANT_ID,
                CHECKOUT_ID,
                DIGEST,
                AMOUNT_MINOR,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(300)));
    }

    private MerchantPaymentService.CreateCommand command() {
        return new MerchantPaymentService.CreateCommand(
                MERCHANT_ID,
                CHECKOUT_ID,
                externalHash(),
                AMOUNT_MINOR,
                "KRW",
                CREDENTIAL_ID,
                IDEMPOTENCY_KEY,
                REQUEST_HASH);
    }

    private String externalHash() {
        return CheckoutHashCodec.toExternalHash(DIGEST);
    }

    private String status(String table) {
        return jdbc.queryForObject("SELECT status FROM " + table, String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }

    private static final class RecordingPaymentGateway implements PaymentGateway {

        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch chargeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseCharge = new CountDownLatch(1);
        private volatile ChargeResult.Outcome outcome = ChargeResult.Outcome.SUCCESS;
        private volatile boolean blockCharge;

        @Override
        public ChargeResult charge(ChargeRequest request) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            calls.incrementAndGet();
            if (blockCharge) {
                chargeStarted.countDown();
                try {
                    assertTrue(releaseCharge.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return ChargeResult.unknown("TEST_INTERRUPTED");
                }
            }
            return switch (outcome) {
                case SUCCESS -> ChargeResult.success(
                        "pg-" + request.pgRequestId(),
                        "APPROVED");
                case FAILED -> ChargeResult.failed(null, "DECLINED");
                case UNKNOWN -> ChargeResult.unknown("PG_TIMEOUT");
            };
        }

        @Override
        public StatusResult lookup(StatusRequest request) {
            throw new AssertionError("Merchant 결제 실행에서 대사 조회를 호출하면 안 됩니다.");
        }
    }
}
