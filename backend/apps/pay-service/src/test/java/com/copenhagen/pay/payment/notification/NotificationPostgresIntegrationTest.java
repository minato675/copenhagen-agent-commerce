package com.copenhagen.pay.payment.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final String MERCHANT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000003";

    private EmbeddedPostgres postgres;
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private NotificationInstallationRepository installations;
    private NotificationOutboxRepository outbox;

    @Test
    void purchaseHandoff_noDeviceIsNotReportedAsSentAndResendKeepsOneEvent() {
        var clock = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        var service = new PurchaseNotificationService(jdbc, clock);
        UUID user = UUID.fromString(USER_ID);
        UUID purchase = UUID.randomUUID();
        service.create(user, purchase);
        service.create(user, purchase);
        assertEquals(1, count("pay_notification_outbox"));
        var dispatch = new NotificationDispatchService(outbox, installations,
                (fid, message) -> { throw new AssertionError("No device must not send"); },
                clock, new PayNotificationProperties(null, null));
        assertTrue(dispatch.processNext());
        assertEquals("FAILED", service.status(user, purchase).getFirst().status());
        assertEquals("NO_ACTIVE_INSTALLATION", service.status(user, purchase).getFirst().errorCode());
        UUID key = UUID.randomUUID();
        service.resend(user, purchase, key);
        service.resend(user, purchase, key);
        assertEquals(1, count("pay_notification_resends"));
        assertEquals("PENDING", service.status(user, purchase).getFirst().status());
        assertTrue(service.status(UUID.randomUUID(), purchase).isEmpty());
    }

    @Test
    void purchasePushContainsPurchaseIdAndFailedDeliveryRemainsRetryable() {
        var clock = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        var service = new PurchaseNotificationService(jdbc, clock);
        UUID purchase = UUID.randomUUID();
        service.create(UUID.fromString(USER_ID), purchase);
        installations.registerActive(USER_ID, "fid-purchase", NOW);
        var dispatch = new NotificationDispatchService(outbox, installations, (fid, message) -> {
            assertEquals(purchase.toString(), message.purchaseRequestId());
            assertEquals(null, message.approvalId());
            assertEquals("OPEN_PURCHASE", message.nextAction());
            throw new NotificationDeliveryException("FCM_SEND_FAILED", false, null);
        }, clock, new PayNotificationProperties(null, null));
        assertTrue(dispatch.processNext());
        assertEquals("FAILED", service.status(UUID.fromString(USER_ID), purchase).getFirst().status());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM pay_notification_deliveries", String.class));
    }

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
        String schema = "notification_" + UUID.randomUUID().toString().replace("-", "");
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
        installations = new JdbcNotificationInstallationRepository(jdbc);
        outbox = new JdbcNotificationOutboxRepository(jdbc, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        PayLogContext.clear();
        if (flyway != null) {
            flyway.clean();
        }
    }

    @Test
    void rebindsFidAndProcessesEachOutboxDeliveryOnce() {
        installations.registerActive("user-old", "fid-1", NOW);
        installations.registerActive(USER_ID, "fid-1", NOW);
        assertTrue(installations.findActiveFidsByUserId("user-old").isEmpty());
        assertEquals(List.of("fid-1"), installations.findActiveFidsByUserId(USER_ID));

        outbox.insertApprovalRequested("approval-1", USER_ID, NOW);
        outbox.insertApprovalRequested("approval-1", USER_ID, NOW);
        assertEquals(1, count("pay_notification_outbox"));

        NotificationOutboxEvent event = outbox.claimNext(
                        NOW,
                        NOW.plusSeconds(60))
                .orElseThrow();
        outbox.ensureDeliveries(event.eventId(), List.of("fid-1"), NOW);
        outbox.pruneInactiveDeliveries(event.eventId());
        NotificationDelivery delivery = outbox.claimNextDelivery(
                        event.eventId(),
                        NOW,
                        NOW.plusSeconds(60))
                .orElseThrow();

        assertEquals("fid-1", delivery.fid());
        assertTrue(outbox.markDeliverySent(delivery, NOW.plusSeconds(1)));
        assertFalse(outbox.summarizeDeliveries(event.eventId()).hasRetryableDeliveries());
        assertTrue(outbox.markEventSent(event, NOW.plusSeconds(1)));
        assertEquals("SENT", status("pay_notification_outbox"));
    }

    @Test
    void storesPaymentResultTargetWithoutStoringCredentialData() {
        insertMerchant();
        insertSession();

        outbox.insertPaymentResult("payment-1", SESSION_ID, PaymentStatus.UNKNOWN, NOW);

        NotificationOutboxEvent event = outbox.claimNext(
                        NOW,
                        NOW.plusSeconds(60))
                .orElseThrow();
        assertEquals(NotificationEventType.PAYMENT_RESULT, event.eventType());
        assertEquals(USER_ID, event.userId());
        assertEquals(PaymentStatus.UNKNOWN, event.paymentStatus());
        assertTrue(event.payloadJson().contains("WAIT_FOR_PAYMENT_STATUS"));
        assertFalse(event.payloadJson().contains("credential"));
        assertFalse(event.payloadJson().contains("mandate"));
    }

    @Test
    void preservesRequestIdInOutboxForWorkerTracing() {
        PayLogContext.put(PayLogContext.REQUEST_ID, "request-123");

        outbox.insertApprovalRequested("approval-1", USER_ID, NOW);

        NotificationOutboxEvent event = outbox.claimNext(
                        NOW,
                        NOW.plusSeconds(60))
                .orElseThrow();

        assertEquals("request-123", event.requestId());
        assertTrue(event.payloadJson().contains("request-123"));
    }

    private void insertMerchant() {
        jdbc.update(
                """
                INSERT INTO merchant_accounts (id, merchant_id, display_name, auth_type, status)
                VALUES (?, 'merchant-test', 'Test Merchant', 'TOKEN', 'ACTIVE')
                """,
                MERCHANT_ACCOUNT_ID);
    }

    private void insertSession() {
        jdbc.update(
                """
                INSERT INTO authorization_sessions
                    (id, purchase_request_id, user_id,
                     total_amount_minor, currency, selected_payment_method_id, status,
                     idempotency_key, request_hash, required_action, expires_at,
                     created_at, updated_at, version)
                VALUES (?, 'purchase-1', ?,
                        1000, 'KRW', NULL, 'PAYMENT_METHOD_REQUIRED',
                        'approval-key-1', ?, 'BIOMETRIC', ?, ?, ?, 0)
                """,
                SESSION_ID,
                USER_ID,
                "b".repeat(64),
                java.sql.Timestamp.from(NOW.plusSeconds(300)),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        jdbc.update(
                """
                INSERT INTO authorization_session_items
                    (id, authorization_session_id, merchant_account_id, external_checkout_id,
                     checkout_digest, display_snapshot, amount_minor, currency, status,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 'checkout-1', ?, '{}'::jsonb, 1000, 'KRW', 'PENDING',
                        ?, ?, 0)
                """,
                java.util.UUID.randomUUID().toString(),
                SESSION_ID,
                MERCHANT_ACCOUNT_ID,
                "a".repeat(64),
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
    }

    private int count(String tableName) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private String status(String tableName) {
        return jdbc.queryForObject(
                "SELECT status FROM " + tableName + " ORDER BY created_at DESC LIMIT 1",
                String.class);
    }
}
