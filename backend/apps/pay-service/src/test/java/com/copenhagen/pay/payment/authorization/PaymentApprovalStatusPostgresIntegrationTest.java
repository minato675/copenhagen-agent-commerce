package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.copenhagen.contract.internalapi.PaymentApprovalNextAction;
import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentApprovalStatusPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String MERCHANT_ID = "merchant-a";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000002";
    private static final String PAYMENT_METHOD_ID = "00000000-0000-0000-0000-000000000003";
    private static final String APPROVED_SESSION_ID =
            "00000000-0000-0000-0000-000000000004";
    private static final String PENDING_SESSION_ID = "00000000-0000-0000-0000-000000000005";
    private static final String CHECKOUT_MANDATE_ID = "00000000-0000-0000-0000-000000000006";
    private static final String PAYMENT_MANDATE_ID = "00000000-0000-0000-0000-000000000007";
    private static final String CREDENTIAL_ID = "00000000-0000-0000-0000-000000000008";
    private static final String PAYMENT_ID = "00000000-0000-0000-0000-000000000009";
    private static final String CHECKOUT_ID = "checkout-123";
    private static final String PURCHASE_REQUEST_ID = "00000000-0000-0000-0000-000000000010";
    private static final String PENDING_PURCHASE_REQUEST_ID =
            "00000000-0000-0000-0000-000000000011";
    private static final String DIGEST = "a".repeat(64);
    private static final String REQUEST_HASH = "b".repeat(64);

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private PaymentApprovalStatusService service;

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
        String schema = "approval_status_"
                + UUID.randomUUID().toString().replace("-", "");
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
        insertBaseFixtures();
        service = new PaymentApprovalStatusService(
                new JdbcPaymentApprovalStatusRepository(jdbc));
    }

    @AfterEach
    void tearDown() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void returnsApprovedSessionWhenPaymentHasNotBeenCreated() {
        insertAuthorizationSession(
                APPROVED_SESSION_ID,
                AuthorizationSessionStatus.APPROVED,
                PAYMENT_METHOD_ID);

        PaymentApprovalStatusResponse response = service.find(APPROVED_SESSION_ID);

        assertEquals(APPROVED_SESSION_ID, response.approvalId());
        assertEquals("APPROVED", response.approvalStatus().name());
        assertNull(response.paymentId());
        assertNull(response.paymentStatus());
        assertEquals(99000, response.amountMinor());
        assertEquals("KRW", response.currency());
        assertNull(response.credentialId());
        assertNull(response.credentialExpiresAt());
        assertEquals("VISA " + "\u2022".repeat(4) + " 1234", response.paymentLabel());
        assertEquals(
                PaymentApprovalNextAction.WAIT_FOR_PAYMENT_CREATION,
                response.nextAction());
    }

    @Test
    void readsPaymentThroughTheCredentialJoinAndPreservesSessionAmount() {
        insertAuthorizationSession(
                APPROVED_SESSION_ID,
                AuthorizationSessionStatus.APPROVED,
                PAYMENT_METHOD_ID);
        insertPaymentChain();
        String authorizationStatusBefore = authorizationStatus(APPROVED_SESSION_ID);
        String paymentStatusBefore = paymentStatus(PAYMENT_ID);
        int attemptCountBefore = count("payment_attempts");
        int transactionCountBefore = count("pg_transactions");
        int receiptCountBefore = count("payment_receipts");

        PaymentApprovalStatusResponse response = service.find(APPROVED_SESSION_ID);

        assertEquals(PAYMENT_ID, response.paymentId());
        assertEquals("UNKNOWN", response.paymentStatus().name());
        assertEquals(99000, response.amountMinor());
        assertEquals("KRW", response.currency());
        assertEquals(CREDENTIAL_ID, response.credentialId());
        assertEquals(NOW.plusSeconds(300), response.credentialExpiresAt());
        assertEquals("VISA " + "\u2022".repeat(4) + " 1234", response.paymentLabel());
        assertEquals(
                PaymentApprovalNextAction.WAIT_FOR_PAYMENT_STATUS,
                response.nextAction());
        assertEquals(authorizationStatusBefore, authorizationStatus(APPROVED_SESSION_ID));
        assertEquals(paymentStatusBefore, paymentStatus(PAYMENT_ID));
        assertEquals(attemptCountBefore, count("payment_attempts"));
        assertEquals(transactionCountBefore, count("pg_transactions"));
        assertEquals(receiptCountBefore, count("payment_receipts"));
    }

    @Test
    void returnsNoNextActionForNonApprovedSessionWithoutPayment() {
        insertAuthorizationSession(
                PENDING_SESSION_ID,
                AuthorizationSessionStatus.PENDING,
                PAYMENT_METHOD_ID);

        PaymentApprovalStatusResponse response = service.find(PENDING_SESSION_ID);

        assertEquals("PENDING", response.approvalStatus().name());
        assertNull(response.paymentId());
        assertNull(response.paymentStatus());
        assertNull(response.nextAction());
    }

    private void insertBaseFixtures() {
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

    private void insertAuthorizationSession(
            String sessionId,
            AuthorizationSessionStatus status,
            String paymentMethodId) {
        boolean approved = status == AuthorizationSessionStatus.APPROVED;
        jdbc.update(
                """
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id,
                     total_amount_minor, currency, selected_payment_method_id, status,
                     idempotency_key, request_hash, required_action, expires_at,
                     approved_at, auth_method, created_at, updated_at, version)
                VALUES (?, ?, ?, 99000, 'KRW', ?, ?, ?, ?,
                        'PIN', ?, ?, ?, ?, ?, 1)
                """,
                sessionId,
                status == AuthorizationSessionStatus.PENDING
                        ? PENDING_PURCHASE_REQUEST_ID
                        : PURCHASE_REQUEST_ID,
                USER_ID,
                paymentMethodId,
                status.name(),
                "approval-key-" + sessionId,
                REQUEST_HASH,
                java.sql.Timestamp.from(NOW.plusSeconds(300)),
                approved ? java.sql.Timestamp.from(NOW) : null,
                approved ? "PIN" : null,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbc.update(
                """
                INSERT INTO authorization_session_items
                    (id, authorization_session_id, merchant_account_id, external_checkout_id,
                     checkout_digest, display_snapshot, amount_minor, currency, status,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, '{}'::jsonb, 99000, 'KRW', 'PENDING', ?, ?, 0)
                """,
                sessionItemId(sessionId),
                sessionId,
                MERCHANT_ACCOUNT_ID,
                CHECKOUT_ID,
                DIGEST,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
    }

    /** 세션당 item 이 하나라 세션 id 에서 결정적으로 만든다. */
    private static String sessionItemId(String sessionId) {
        return java.util.UUID.nameUUIDFromBytes(("item:" + sessionId).getBytes(
                java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private void insertPaymentChain() {
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
                APPROVED_SESSION_ID,
                sessionItemId(APPROVED_SESSION_ID),
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
                VALUES (?, ?, ?, 'payment-jti', 'transaction-001', ?, ?, 1, 'KRW',
                        'provider-token-ref', 'encrypted', ?, 'ISSUED', ?, ?)
                """,
                PAYMENT_MANDATE_ID,
                APPROVED_SESSION_ID,
                sessionItemId(APPROVED_SESSION_ID),
                MERCHANT_ID,
                CHECKOUT_ID,
                "d".repeat(64),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(300)));
        jdbc.update(
                """
                INSERT INTO payment_credential_tokens
                    (id, authorization_session_id, session_item_id, checkout_mandate_id,
                     payment_mandate_id,
                     token_hash, token_ciphertext, merchant_id, external_checkout_id,
                     checkout_digest, amount_minor, currency, status, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'encrypted', ?, ?, ?, 1, 'KRW', 'ISSUED', ?, ?)
                """,
                CREDENTIAL_ID,
                APPROVED_SESSION_ID,
                sessionItemId(APPROVED_SESSION_ID),
                CHECKOUT_MANDATE_ID,
                PAYMENT_MANDATE_ID,
                "e".repeat(64),
                MERCHANT_ID,
                CHECKOUT_ID,
                DIGEST,
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW.plusSeconds(300)));
        jdbc.update(
                """
                INSERT INTO payments
                    (id, merchant_account_id, external_checkout_id, purchase_request_id,
                     credential_token_id, amount_minor, currency, status,
                     idempotency_key, request_hash)
                VALUES (?, ?, ?, ?, ?, 1, 'KRW', 'UNKNOWN', 'payment-key', ?)
                """,
                PAYMENT_ID,
                MERCHANT_ACCOUNT_ID,
                CHECKOUT_ID,
                PURCHASE_REQUEST_ID,
                CREDENTIAL_ID,
                REQUEST_HASH);
    }

    private String authorizationStatus(String sessionId) {
        return jdbc.queryForObject(
                "SELECT status FROM authorization_sessions WHERE id = ?",
                String.class,
                sessionId);
    }

    private String paymentStatus(String paymentId) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?",
                String.class,
                paymentId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
