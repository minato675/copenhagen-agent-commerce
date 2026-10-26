package com.copenhagen.pay.payment.credential.mandate;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * mandate·credential INSERT는 단위 테스트가 JDBC를 타지 않아 결함을 놓쳤다.
 * pgjdbc는 Instant를 그대로 받으면 SQL 타입을 추론하지 못하고 던진다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MandateCredentialPostgresIntegrationTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-09-15T00:00:00Z");
    private static final Instant EXPIRES_AT = ISSUED_AT.plusSeconds(300);
    private static final String USER_ID = "00000000-0000-0000-0000-000000000003";
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PAYMENT_METHOD_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000004";
    private static final String SESSION_ITEM_ID = "00000000-0000-0000-0000-000000000014";
    private static final String PURCHASE_REQUEST_ID = "00000000-0000-0000-0000-000000000005";

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private JdbcMandateCredentialRepository repository;

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
        String schema = "mandate_credential_" + UUID.randomUUID().toString().replace("-", "");
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
        repository = new JdbcMandateCredentialRepository(jdbc);
        insertReferenceData();
    }

    @AfterEach
    void tearDown() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void issuanceInsertsAllThreeRowsWithTimestampBoundInstants() {
        repository.insert(issuance());

        assertEquals(1, countById("checkout_mandates", "cm-001"));
        assertEquals(1, countById("payment_mandates", "pm-001"));
        assertEquals(1, countById("payment_credential_tokens", "pct-001"));
    }

    @Test
    void issuedAtAndExpiresAtRoundTripAsTimestamps() {
        repository.insert(issuance());

        assertEquals(Timestamp.from(ISSUED_AT), issuedAt("checkout_mandates", "cm-001"));
        assertEquals(Timestamp.from(EXPIRES_AT), expiresAt("checkout_mandates", "cm-001"));
        assertEquals(Timestamp.from(ISSUED_AT), issuedAt("payment_mandates", "pm-001"));
        assertEquals(Timestamp.from(EXPIRES_AT), expiresAt("payment_mandates", "pm-001"));
        assertEquals(Timestamp.from(ISSUED_AT), issuedAt("payment_credential_tokens", "pct-001"));
        assertEquals(Timestamp.from(EXPIRES_AT), expiresAt("payment_credential_tokens", "pct-001"));
    }

    private MandateCredentialRepository.Issuance issuance() {
        return new MandateCredentialRepository.Issuance(
                "cm-001",
                SESSION_ID,
                SESSION_ITEM_ID,
                "jti-checkout-001",
                "pay-service",
                "merchant-test",
                "0".repeat(64),
                "checkout-artifact",
                "b".repeat(64),
                ISSUED_AT,
                EXPIRES_AT,
                "pm-001",
                "jti-payment-001",
                "txn-001",
                "merchant-test",
                "checkout-test",
                99000L,
                "KRW",
                PAYMENT_METHOD_ID,
                "payment-artifact",
                "c".repeat(64),
                "pct-001",
                "d".repeat(64),
                "credential-ciphertext");
    }

    private int countById(String table, String id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    private Timestamp issuedAt(String table, String id) {
        return jdbc.queryForObject(
                "SELECT issued_at FROM " + table + " WHERE id = ?", Timestamp.class, id);
    }

    private Timestamp expiresAt(String table, String id) {
        return jdbc.queryForObject(
                "SELECT expires_at FROM " + table + " WHERE id = ?", Timestamp.class, id);
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
                        'PENDING', 'approval-test', ?, 'PIN', ?, ?, ?, 0)
                """,
                SESSION_ID,
                PURCHASE_REQUEST_ID,
                USER_ID,
                PAYMENT_METHOD_ID,
                "a".repeat(64),
                Timestamp.from(EXPIRES_AT),
                Timestamp.from(ISSUED_AT),
                Timestamp.from(ISSUED_AT));
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
                "0".repeat(64),
                Timestamp.from(ISSUED_AT),
                Timestamp.from(ISSUED_AT));
    }
}
