package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Timestamp;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 결제수단 미선택 세션은 만료될 수 있어야 한다. 예전 제약은 종료 상태에도 결제수단을
 * 요구해서, 조회 시 도는 만료 스윕이 제약 위반으로 터졌다(목록·상세 조회 500).
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationSessionExpiryPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:10:00Z");
    private static final Instant EXPIRED_AT = NOW.minusSeconds(600);
    private static final String USER_ID = "00000000-0000-0000-0000-000000000003";
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PAYMENT_METHOD_ID = "00000000-0000-0000-0000-000000000002";

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private JdbcAuthorizationSessionRepository repository;

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
        String schema = "session_expiry_" + UUID.randomUUID().toString().replace("-", "");
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
        repository = new JdbcAuthorizationSessionRepository(jdbc);
        insertReferenceData();
    }

    @AfterEach
    void tearDown() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void overduePaymentMethodRequiredSessionExpiresForUserSweep() {
        insertSession("session-pmr", "PAYMENT_METHOD_REQUIRED", null);

        assertEquals(1, repository.expireOverdueForUser(USER_ID, NOW));
        assertEquals("EXPIRED", status("session-pmr"));
    }

    @Test
    void overduePaymentMethodRequiredSessionExpiresForDetailSweep() {
        insertSession("session-pmr", "PAYMENT_METHOD_REQUIRED", null);

        assertEquals(1, repository.expireOverdueByIdAndUser("session-pmr", USER_ID, NOW));
        assertEquals("EXPIRED", status("session-pmr"));
    }

    @Test
    void overduePendingSessionStillExpires() {
        insertSession("session-pending", "PENDING", PAYMENT_METHOD_ID);

        assertEquals(1, repository.expireOverdueForUser(USER_ID, NOW));
        assertEquals("EXPIRED", status("session-pending"));
    }

    @Test
    void pendingSessionStillRequiresSelectedPaymentMethod() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertSession("session-invalid", "PENDING", null));
    }

    @Test
    void paymentMethodRequiredSessionStillRejectsSelectedPaymentMethod() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertSession("session-invalid", "PAYMENT_METHOD_REQUIRED", PAYMENT_METHOD_ID));
    }

    private String status(String sessionId) {
        return jdbc.queryForObject(
                "SELECT status FROM authorization_sessions WHERE id = ?", String.class, sessionId);
    }

    private void insertSession(String sessionId, String status, String paymentMethodId) {
        jdbc.update(
                """
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id, total_amount_minor, currency,
                     selected_payment_method_id, status,
                     idempotency_key, request_hash, required_action, expires_at,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 99000, 'KRW', ?,
                        ?, ?, ?, 'PIN', ?, ?, ?, 0)
                """,
                sessionId,
                UUID.randomUUID().toString(),
                USER_ID,
                paymentMethodId,
                status,
                "idem-" + sessionId,
                "a".repeat(64),
                Timestamp.from(EXPIRED_AT),
                Timestamp.from(EXPIRED_AT),
                Timestamp.from(EXPIRED_AT));
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
                sessionId,
                MERCHANT_ACCOUNT_ID,
                "0".repeat(64),
                Timestamp.from(EXPIRED_AT),
                Timestamp.from(EXPIRED_AT));
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
    }
}
