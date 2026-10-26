package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.payment.policy.AuthorizationSessionItemStatus;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V15 스키마 검증.
 *
 * <p>V14 는 {@code chk_authorization_sessions_payment_method_state} 가 만료 스윕의
 * {@code PAYMENT_METHOD_REQUIRED -> EXPIRED} 전이를 막아 승인 목록·상세 조회가 영구히 500 이
 * 되었던 사건의 수정이다. V15 는 컬럼 제거와 백필을 동반하므로 같은 유형의 회귀 여지가 더 크다.
 * 이 테스트는 세션과 item 의 <b>모든</b> 상태 전이가 DB 제약을 통과하는지 전수로 확인한다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationSessionSchemaPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    // V12 가 시드한 merchant_accounts 를 그대로 쓴다.
    private static final String MERCHANT_ACCOUNT_A = "c9f1a2b3-4d5e-4f61-8a72-9b3c4d5e6f71";
    private static final String MERCHANT_ACCOUNT_B = "d0a2b3c4-5e6f-4072-9b83-0c4d5e6f7182";

    private EmbeddedPostgres postgres;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcAuthorizationSessionRepository repository;
    private String paymentMethodId;

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
        String schema = "session_schema_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:" + postgres.getPort()
                        + "/postgres?currentSchema=" + schema,
                "postgres",
                "postgres");
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcAuthorizationSessionRepository(jdbc);
        paymentMethodId = insertPaymentMethod();
    }

    @Test
    void everySessionStatusIsAcceptedByTheCheckConstraints() {
        // PAYMENT_METHOD_REQUIRED 는 결제수단이 없어야 하고, 나머지 종료 상태는 있든 없든 허용된다.
        for (AuthorizationSessionStatus status : AuthorizationSessionStatus.values()) {
            String sessionId = UUID.randomUUID().toString();
            insertSession(sessionId, UUID.randomUUID().toString(), status, null);
            assertEquals(
                    status,
                    repository.findStatus(sessionId).orElseThrow(),
                    status + " 세션을 저장할 수 없습니다.");
        }
    }

    @Test
    void expirySweepMovesUnselectedPendingSessionToExpired() {
        // V14 가 터졌던 바로 그 전이. 결제수단 없이 만료되는 것이 정상이다.
        String sessionId = UUID.randomUUID().toString();
        insertSession(
                sessionId,
                UUID.randomUUID().toString(),
                AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED,
                NOW.minusSeconds(10));

        int expired = repository.expireOverdueForUser(USER_ID, NOW);

        assertEquals(1, expired);
        assertEquals(
                AuthorizationSessionStatus.EXPIRED,
                repository.findStatus(sessionId).orElseThrow());
    }

    @Test
    void expirySweepAlsoExpiresPendingItemsOfNeverApprovedSessions() {
        String sessionId = UUID.randomUUID().toString();
        insertSession(
                sessionId,
                UUID.randomUUID().toString(),
                AuthorizationSessionStatus.PENDING,
                NOW.minusSeconds(10));
        String itemId = insertItem(sessionId, MERCHANT_ACCOUNT_A, "checkout-a", 50000);

        repository.expireOverdueForUser(USER_ID, NOW);

        assertEquals(
                AuthorizationSessionItemStatus.EXPIRED,
                repository.findItemStatus(itemId).orElseThrow());
    }

    @Test
    void expirySweepLeavesItemsOfApprovedSessionsAlone() {
        // 승인된 세션은 credential 이 나가 판매처가 결제 중일 수 있다. item 을 만료시키면
        // 결제 성공을 기록할 수 없게 된다.
        String sessionId = UUID.randomUUID().toString();
        insertSession(
                sessionId,
                UUID.randomUUID().toString(),
                AuthorizationSessionStatus.APPROVED,
                NOW.minusSeconds(10),
                NOW.minusSeconds(60));
        String itemId = insertItem(sessionId, MERCHANT_ACCOUNT_A, "checkout-a", 50000);

        repository.expireOverdueForUser(USER_ID, NOW);

        assertEquals(
                AuthorizationSessionItemStatus.PENDING,
                repository.findItemStatus(itemId).orElseThrow());
    }

    @Test
    void everyItemStatusTransitionFromPendingIsAccepted() {
        String sessionId = UUID.randomUUID().toString();
        insertSession(
                sessionId,
                UUID.randomUUID().toString(),
                AuthorizationSessionStatus.APPROVED,
                NOW.plusSeconds(300),
                NOW);
        for (AuthorizationSessionItemStatus target : AuthorizationSessionItemStatus.values()) {
            if (target == AuthorizationSessionItemStatus.PENDING) {
                continue;
            }
            String itemId = insertItem(
                    sessionId,
                    target == AuthorizationSessionItemStatus.COMPLETED
                            ? MERCHANT_ACCOUNT_A
                            : MERCHANT_ACCOUNT_B,
                    "checkout-" + target,
                    10000);
            assertTrue(
                    repository.transitionItemStatus(
                            itemId,
                            AuthorizationSessionItemStatus.PENDING,
                            target),
                    "item 전이 PENDING -> " + target + " 가 거절되었습니다.");
            jdbc.update("DELETE FROM authorization_session_items WHERE id = ?", itemId);
        }
    }

    @Test
    void onlyOneActiveSessionIsAllowedPerPurchaseRequest() {
        String purchaseRequestId = UUID.randomUUID().toString();
        insertSession(
                UUID.randomUUID().toString(),
                purchaseRequestId,
                AuthorizationSessionStatus.PENDING,
                NOW.plusSeconds(300));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertSession(
                        UUID.randomUUID().toString(),
                        purchaseRequestId,
                        AuthorizationSessionStatus.PENDING,
                        NOW.plusSeconds(300)));
    }

    @Test
    void terminalSessionsDoNotBlockANewSessionForTheSamePurchaseRequest() {
        String purchaseRequestId = UUID.randomUUID().toString();
        insertSession(
                UUID.randomUUID().toString(),
                purchaseRequestId,
                AuthorizationSessionStatus.EXPIRED,
                NOW.minusSeconds(10));

        String retried = UUID.randomUUID().toString();
        insertSession(retried, purchaseRequestId, AuthorizationSessionStatus.PENDING,
                NOW.plusSeconds(300));

        assertEquals(
                AuthorizationSessionStatus.PENDING,
                repository.findStatus(retried).orElseThrow());
    }

    @Test
    void sessionCarriesEveryMerchantItem() {
        String sessionId = UUID.randomUUID().toString();
        insertSession(
                sessionId,
                UUID.randomUUID().toString(),
                AuthorizationSessionStatus.PENDING,
                NOW.plusSeconds(300));
        insertItem(sessionId, MERCHANT_ACCOUNT_A, "checkout-a", 92000);
        insertItem(sessionId, MERCHANT_ACCOUNT_B, "checkout-b", 39000);

        AuthorizationSession loaded = repository
                .findByIdAndUserId(sessionId, USER_ID)
                .orElseThrow();

        assertEquals(2, loaded.merchantCount());
        assertEquals(
                List.of("merchant-a", "merchant-b"),
                loaded.items().stream().map(AuthorizationSessionItem::merchantId).sorted().toList());
        assertEquals(
                List.of("Copenhagen Demo Store", "노을상점"),
                loaded.items().stream()
                        .map(AuthorizationSessionItem::merchantName)
                        .sorted()
                        .toList());
    }

    @Test
    void theSameMerchantCannotAppearTwiceInOneSession() {
        String sessionId = UUID.randomUUID().toString();
        insertSession(
                sessionId,
                UUID.randomUUID().toString(),
                AuthorizationSessionStatus.PENDING,
                NOW.plusSeconds(300));
        insertItem(sessionId, MERCHANT_ACCOUNT_A, "checkout-a", 10000);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertItem(sessionId, MERCHANT_ACCOUNT_A, "checkout-a-2", 10000));
    }

    @Test
    void idempotencyKeyIsScopedToTheUserNotTheMerchant() {
        String sharedKey = "idempotency-shared";
        insertSession(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                AuthorizationSessionStatus.EXPIRED,
                NOW.minusSeconds(10),
                null,
                sharedKey);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertSession(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        AuthorizationSessionStatus.EXPIRED,
                        NOW.minusSeconds(10),
                        null,
                        sharedKey));
        assertFalse(repository.findByUserAndIdempotencyKey(USER_ID, sharedKey).isEmpty());
    }

    private void insertSession(
            String sessionId,
            String purchaseRequestId,
            AuthorizationSessionStatus status,
            Instant expiresAt) {
        insertSession(sessionId, purchaseRequestId, status, expiresAt, null);
    }

    private void insertSession(
            String sessionId,
            String purchaseRequestId,
            AuthorizationSessionStatus status,
            Instant expiresAt,
            Instant approvedAt) {
        insertSession(
                sessionId,
                purchaseRequestId,
                status,
                expiresAt,
                approvedAt,
                "idempotency-" + sessionId);
    }

    private void insertSession(
            String sessionId,
            String purchaseRequestId,
            AuthorizationSessionStatus status,
            Instant expiresAt,
            Instant approvedAt,
            String idempotencyKey) {
        boolean requiresPaymentMethod =
                status != AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                        && (status == AuthorizationSessionStatus.PENDING
                        || status == AuthorizationSessionStatus.APPROVED
                        || status == AuthorizationSessionStatus.COMPLETED);
        String selectedPaymentMethodId = requiresPaymentMethod ? paymentMethodId : null;
        jdbc.update(
                """
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id, total_amount_minor, currency,
                     selected_payment_method_id, status, idempotency_key, request_hash,
                     required_action, expires_at, approved_at, created_at, updated_at, version)
                VALUES (?, ?, ?, 131000, 'KRW', ?, ?, ?, ?, 'BIOMETRIC', ?, ?, ?, ?, 0)
                """,
                sessionId,
                purchaseRequestId,
                USER_ID,
                selectedPaymentMethodId,
                status.name(),
                idempotencyKey,
                "0".repeat(64),
                Timestamp.from(expiresAt == null ? NOW.plusSeconds(300) : expiresAt),
                approvedAt == null ? null : Timestamp.from(approvedAt),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }

    private String insertPaymentMethod() {
        String newPaymentMethodId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO payment_methods
                    (id, user_id, provider, provider_token_ref, status, is_default)
                VALUES (?, ?, 'PG_MOCK', ?, 'ACTIVE', TRUE)
                """,
                newPaymentMethodId,
                USER_ID,
                "token-" + newPaymentMethodId);
        return newPaymentMethodId;
    }

    private String insertItem(
            String sessionId,
            String merchantAccountId,
            String externalCheckoutId,
            long amountMinor) {
        String itemId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO authorization_session_items
                    (id, authorization_session_id, merchant_account_id, external_checkout_id,
                     checkout_digest, display_snapshot, amount_minor, currency, status,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, '{}'::jsonb, ?, 'KRW', 'PENDING', ?, ?, 0)
                """,
                itemId,
                sessionId,
                merchantAccountId,
                externalCheckoutId,
                "0".repeat(64),
                amountMinor,
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        return itemId;
    }
}
