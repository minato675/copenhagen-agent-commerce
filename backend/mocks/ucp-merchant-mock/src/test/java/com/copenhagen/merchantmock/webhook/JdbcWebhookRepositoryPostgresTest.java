package com.copenhagen.merchantmock.webhook;

import java.time.OffsetDateTime;
import java.util.List;

import javax.sql.DataSource;

import com.copenhagen.merchantmock.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcWebhookRepository 의 SQL 검증(Jira 181, 294 방식). 두 UPDATE 의 {@code status = 'PENDING'} 조건과
 * jsonb 왕복이 핵심이다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWebhookRepositoryPostgresTest {

    private static final OffsetDateTime NOW = WebhookTestSupport.NOW_SEOUL;
    private static final WebhookDelivery.Signed SIGNED =
            new WebhookDelivery.Signed("sig1=(...);created=1;keyid=\"k\"", "sig1=:abc:", "sha-256=:d:");

    private EmbeddedPostgresSupport postgres;
    private JdbcWebhookRepository repository;
    private JdbcTemplate jdbc;

    @BeforeAll
    void startPostgres() throws Exception {
        this.postgres = EmbeddedPostgresSupport.start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, null).migrate();
        this.jdbc = postgres.jdbcFor(dataSource);
        this.repository = new JdbcWebhookRepository(jdbc);
        jdbc.update("INSERT INTO checkout_sessions (id, merchant_id, status) VALUES (?, 'merchant-a', 'COMPLETED')",
                WebhookTestSupport.CHECKOUT_ID);
        jdbc.update("""
                INSERT INTO orders (id, merchant_id,
                                    checkout_session_id, status, total_amount, currency, raw_order_json)
                VALUES (?, 'merchant-a', ?, 'CREATED', 92000, 'KRW', ?::jsonb)
                """, WebhookTestSupport.ORDER_ID, WebhookTestSupport.CHECKOUT_ID, WebhookTestSupport.RAW_ORDER_JSON);
    }

    @Test
    @DisplayName("넣고 다시 읽으면 값이 그대로고 페이로드는 두 번 읽어도 같은 바이트다")
    void insertAndFindById_newRow_roundTripsAndPayloadIsStable() {
        // given
        WebhookDelivery delivery = WebhookTestSupport.pendingCreated("webhook-1");

        // when
        repository.insert(delivery);
        WebhookDelivery first = repository.findById("webhook-1").orElseThrow();
        WebhookDelivery second = repository.findById("webhook-1").orElseThrow();

        // then
        assertEquals(WebhookTestSupport.ORDER_ID, first.orderId());
        assertEquals(WebhookEventType.ORDER_CREATED, first.eventType());
        assertEquals(WebhookStatus.PENDING, first.status());
        assertEquals(0, first.attemptCount());
        assertNull(first.signed());
        assertNull(first.responseStatus());
        assertEquals(NOW.plus(WebhookTestSupport.FIRST_DELAY).toInstant(), first.nextAttemptAt().toInstant());
        assertTrue(first.payloadJson().contains("\"order\""));
        assertEquals(first.payloadJson(), second.payloadJson(), "재전송은 같은 바이트를 보낸다(불변 조건 8번)");
    }

    @Test
    @DisplayName("전달 표시는 PENDING 행에만 되고 두 번째는 false 다")
    void markDelivered_pendingRow_closesOnce() {
        // given
        repository.insert(WebhookTestSupport.pendingCreated("webhook-1"));

        // when
        boolean first = repository.markDelivered("webhook-1", 204, SIGNED);
        boolean again = repository.markDelivered("webhook-1", 200, SIGNED);

        // then
        assertTrue(first);
        assertFalse(again);
        WebhookDelivery stored = repository.findById("webhook-1").orElseThrow();
        assertEquals(WebhookStatus.DELIVERED, stored.status());
        assertEquals(204, stored.responseStatus());
        assertEquals(1, stored.attemptCount());
        assertNull(stored.nextAttemptAt());
        assertEquals(SIGNED, stored.signed());
    }

    @Test
    @DisplayName("재시도 표시는 횟수를 올리고 다음 시각을 적되 PENDING 을 유지한다")
    void markRetry_pendingRow_incrementsAndKeepsPending() {
        // given
        repository.insert(WebhookTestSupport.pendingCreated("webhook-1"));

        // when
        boolean first = repository.markRetry("webhook-1", 503, NOW.plusSeconds(10), SIGNED);
        boolean second = repository.markRetry("webhook-1", null, NOW.plusSeconds(30), SIGNED);

        // then
        assertTrue(first);
        assertTrue(second);
        WebhookDelivery stored = repository.findById("webhook-1").orElseThrow();
        assertEquals(WebhookStatus.PENDING, stored.status());
        assertEquals(2, stored.attemptCount());
        assertNull(stored.responseStatus(), "연결 실패는 응답 코드가 없다");
        assertEquals(NOW.plusSeconds(30).toInstant(), stored.nextAttemptAt().toInstant());
    }

    @Test
    @DisplayName("전달된 행에 재시도 표시를 하면 false 다")
    void markRetry_deliveredRow_returnsFalse() {
        // given
        repository.insert(WebhookTestSupport.pendingCreated("webhook-1"));
        repository.markDelivered("webhook-1", 204, SIGNED);

        // when & then
        assertFalse(repository.markRetry("webhook-1", 500, NOW.plusSeconds(10), SIGNED));
        assertEquals(WebhookStatus.DELIVERED, repository.findById("webhook-1").orElseThrow().status());
    }

    @Test
    @DisplayName("주문별 이력은 생성 순서다")
    void findByOrderId_multipleRows_orderedByCreation() {
        // given
        repository.insert(WebhookTestSupport.pendingCreated("webhook-a"));
        repository.insert(new WebhookDelivery("webhook-b", WebhookTestSupport.ORDER_ID,
                WebhookEventType.ORDER_UPDATED, "{\"order\":{\"id\":\"order-1\"}}", null, WebhookStatus.PENDING,
                0, NOW.plusMinutes(1), null, NOW.plusMinutes(1)));

        // when
        List<WebhookDelivery> rows = repository.findByOrderId(WebhookTestSupport.ORDER_ID);

        // then
        assertEquals(List.of("webhook-a", "webhook-b"), rows.stream().map(WebhookDelivery::webhookId).toList());
        assertNotEquals(rows.get(0).eventType(), rows.get(1).eventType());
    }

    @Test
    @DisplayName("없는 주문이나 모르는 이벤트 종류는 DB 가 거절한다")
    void insert_constraintViolations_rejected() {
        // when & then
        assertThrows(RuntimeException.class, () -> repository.insert(WebhookDelivery.pending(
                "webhook-x", "no-such-order", WebhookEventType.ORDER_CREATED, "{}", NOW,
                WebhookTestSupport.FIRST_DELAY)));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                INSERT INTO webhook_deliveries (webhook_id, order_id, event_type, payload_json)
                VALUES ('webhook-y', ?, 'order.deleted', '{}'::jsonb)
                """, WebhookTestSupport.ORDER_ID));
    }

    @Test
    @DisplayName("재전송 워커가 쓰는 인덱스를 실행 계획이 탄다")
    void pendingScan_query_usesPendingIndex() {
        // given
        jdbc.execute("SET enable_seqscan = off");

        // when
        List<String> plan = jdbc.queryForList("""
                EXPLAIN SELECT webhook_id FROM webhook_deliveries
                 WHERE status = 'PENDING' AND next_attempt_at <= ?
                 ORDER BY next_attempt_at LIMIT 100
                """, String.class, NOW);

        // then
        assertTrue(String.join(System.lineSeparator(), plan).contains("ix_webhook_pending"), plan.toString());
    }
    /** {@code attemptCount} 번 실패해 다음 시도가 {@code dueAt} 으로 잡힌 행을 심는다. */
    private void putPending(String webhookId, int attemptCount, OffsetDateTime dueAt) {
        repository.insert(WebhookTestSupport.pendingCreated(webhookId));
        jdbc.update("UPDATE webhook_deliveries SET attempt_count = ?, next_attempt_at = ? WHERE webhook_id = ?",
                attemptCount, dueAt, webhookId);
    }

    private String statusOf(String webhookId) {
        return repository.findById(webhookId).orElseThrow().status().name();
    }

    @Test
    @DisplayName("claimDue 는 기한이 된 PENDING 만 집고 next_attempt_at 을 리스로 민다")
    void claimDue_duePendingRow_returnsIdAndPushesNextAttempt() {
        // given
        putPending("webhook-due", 1, NOW);
        putPending("webhook-later", 1, NOW.plusMinutes(5));

        // when
        OffsetDateTime leaseUntil = NOW.plusMinutes(1);
        List<String> claimed = repository.claimDue(NOW, leaseUntil, 8, 100);

        // then
        assertEquals(List.of("webhook-due"), claimed);
        assertEquals(leaseUntil.toInstant(),
                repository.findById("webhook-due").orElseThrow().nextAttemptAt().toInstant());
        assertEquals(NOW.plusMinutes(5).toInstant(),
                repository.findById("webhook-later").orElseThrow().nextAttemptAt().toInstant());
    }

    @Test
    @DisplayName("리스 때문에 연속으로 두 번 부르면 둘째는 비어 나온다")
    void claimDue_calledTwiceInARow_secondCallClaimsNothing() {
        // given
        putPending("webhook-due", 1, NOW);

        // when
        List<String> first = repository.claimDue(NOW, NOW.plusMinutes(1), 8, 100);
        List<String> second = repository.claimDue(NOW.plusSeconds(1), NOW.plusMinutes(1), 8, 100);

        // then
        assertEquals(List.of("webhook-due"), first);
        assertTrue(second.isEmpty());
    }

    @Test
    @DisplayName("claimDue 는 DELIVERED·FAILED 와 한도를 넘긴 행을 집지 않는다")
    void claimDue_terminalOrExhaustedRows_notClaimed() {
        // given
        putPending("webhook-delivered", 1, NOW);
        repository.markDelivered("webhook-delivered", 204, SIGNED);
        putPending("webhook-failed", 1, NOW);
        jdbc.update("UPDATE webhook_deliveries SET status = 'FAILED' WHERE webhook_id = 'webhook-failed'");
        putPending("webhook-exhausted", 8, NOW);

        // when
        List<String> claimed = repository.claimDue(NOW, NOW.plusMinutes(1), 8, 100);

        // then
        assertTrue(claimed.isEmpty(), claimed.toString());
    }

    @Test
    @DisplayName("claimDue 는 배치 크기를 지키고 기한이 이른 것부터 집는다")
    void claimDue_moreDueRowsThanLimit_claimsOldestFirst() {
        // given
        putPending("webhook-late", 1, NOW.minusSeconds(1));
        putPending("webhook-early", 1, NOW.minusMinutes(1));

        // when
        List<String> claimed = repository.claimDue(NOW, NOW.plusMinutes(1), 8, 1);

        // then
        assertEquals(List.of("webhook-early"), claimed);
    }

    @Test
    @DisplayName("failExhausted 는 한도를 넘긴 PENDING 만 FAILED 로 닫는다")
    void failExhausted_pendingRowsOverLimit_closedAsFailed() {
        // given
        putPending("webhook-exhausted", 8, NOW);
        putPending("webhook-one-left", 7, NOW);
        putPending("webhook-delivered", 9, NOW);
        repository.markDelivered("webhook-delivered", 204, SIGNED);

        // when
        int closed = repository.failExhausted(8, 100);

        // then
        assertEquals(1, closed);
        assertEquals("FAILED", statusOf("webhook-exhausted"));
        assertEquals("PENDING", statusOf("webhook-one-left"));
        assertEquals("DELIVERED", statusOf("webhook-delivered"));
    }

    @Test
    @DisplayName("FAILED 로 닫힌 행은 서명 기록과 시도 횟수를 그대로 남긴다")
    void failExhausted_closedRow_keepsAttemptHistory() {
        // given
        putPending("webhook-exhausted", 3, NOW);
        repository.markRetry("webhook-exhausted", 500, NOW.plusMinutes(2), SIGNED);

        // when
        repository.failExhausted(4, 100);

        // then
        WebhookDelivery row = repository.findById("webhook-exhausted").orElseThrow();
        assertEquals(WebhookStatus.FAILED, row.status());
        assertEquals(4, row.attemptCount());
        assertEquals(500, row.responseStatus());
        assertEquals(SIGNED, row.signed());
    }
}
