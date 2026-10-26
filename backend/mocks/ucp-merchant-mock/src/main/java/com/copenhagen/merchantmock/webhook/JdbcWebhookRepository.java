package com.copenhagen.merchantmock.webhook;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * webhook_deliveries 를 읽고 쓴다. JdbcTemplate 만 쓴다(DESIGN.md 4절).
 *
 * <p>{@code payload_json} 은 jsonb 라 PostgreSQL 이 키 순서와 공백을 정규화한다. 그래서 보내는 바이트는
 * 항상 이 테이블에서 다시 읽은 {@code payload_json::text} 다. 첫 발송도 커밋 뒤 행을 다시 읽어 보낸다.
 * 그래야 첫 발송과 재전송의 바이트가 같다(불변 조건 8번).
 */
@Repository
public class JdbcWebhookRepository implements WebhookRepository {

    private static final String COLUMNS = """
            webhook_id, order_id, event_type, payload_json::text, signature_input, signature, content_digest,
            status, attempt_count, next_attempt_at, response_status, created_at
            """;

    private static final String INSERT = """
            INSERT INTO webhook_deliveries
                (webhook_id, order_id, event_type, payload_json, status, attempt_count, next_attempt_at, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_ID = "SELECT " + COLUMNS + " FROM webhook_deliveries WHERE webhook_id = ?";

    private static final String SELECT_BY_ORDER = "SELECT " + COLUMNS
            + " FROM webhook_deliveries WHERE order_id = ? ORDER BY created_at, webhook_id";

    private static final String MARK_DELIVERED = """
            UPDATE webhook_deliveries
               SET status = 'DELIVERED', response_status = ?, attempt_count = attempt_count + 1,
                   next_attempt_at = NULL, signature_input = ?, signature = ?, content_digest = ?
             WHERE webhook_id = ? AND status = 'PENDING'
            """;

    private static final String MARK_RETRY = """
            UPDATE webhook_deliveries
               SET response_status = ?, attempt_count = attempt_count + 1, next_attempt_at = ?,
                   signature_input = ?, signature = ?, content_digest = ?
             WHERE webhook_id = ? AND status = 'PENDING'
            """;

    private static final String FAIL_EXHAUSTED = """
            UPDATE webhook_deliveries
               SET status = 'FAILED'
             WHERE webhook_id IN (SELECT webhook_id
                                    FROM webhook_deliveries
                                   WHERE status = 'PENDING'
                                     AND attempt_count >= ?
                                   ORDER BY next_attempt_at
                                   LIMIT ?)
               AND status = 'PENDING'
            """;

    private static final String CLAIM_DUE = """
            UPDATE webhook_deliveries
               SET next_attempt_at = ?
             WHERE webhook_id IN (SELECT webhook_id
                                    FROM webhook_deliveries
                                   WHERE status = 'PENDING'
                                     AND next_attempt_at <= ?
                                     AND attempt_count < ?
                                   ORDER BY next_attempt_at
                                   LIMIT ?)
               AND status = 'PENDING'
            RETURNING webhook_id
            """;

    private static final RowMapper<WebhookDelivery> ROW_MAPPER = (rs, rowNum) -> new WebhookDelivery(
            rs.getString("webhook_id"),
            rs.getString("order_id"),
            WebhookEventType.fromValue(rs.getString("event_type")),
            rs.getString("payload_json"),
            rs.getString("signature_input") == null ? null : new WebhookDelivery.Signed(
                    rs.getString("signature_input"), rs.getString("signature"), rs.getString("content_digest")),
            WebhookStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt_count"),
            rs.getObject("next_attempt_at", OffsetDateTime.class),
            rs.getObject("response_status", Integer.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public JdbcWebhookRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(WebhookDelivery d) {
        jdbcTemplate.update(INSERT,
                d.webhookId(), d.orderId(), d.eventType().value(), d.payloadJson(), d.status().name(),
                d.attemptCount(), d.nextAttemptAt(), d.createdAt());
    }

    @Override
    public Optional<WebhookDelivery> findById(String webhookId) {
        return jdbcTemplate.query(SELECT_BY_ID, ROW_MAPPER, webhookId).stream().findFirst();
    }

    @Override
    public List<WebhookDelivery> findByOrderId(String orderId) {
        return jdbcTemplate.query(SELECT_BY_ORDER, ROW_MAPPER, orderId);
    }

    @Override
    public boolean markDelivered(String webhookId, int responseStatus, WebhookDelivery.Signed signed) {
        return jdbcTemplate.update(MARK_DELIVERED,
                responseStatus, signed.signatureInput(), signed.signature(), signed.contentDigest(),
                webhookId) == 1;
    }

    @Override
    public boolean markRetry(String webhookId, Integer responseStatus, OffsetDateTime nextAttemptAt,
            WebhookDelivery.Signed signed) {
        return jdbcTemplate.update(MARK_RETRY,
                responseStatus, nextAttemptAt, signed.signatureInput(), signed.signature(),
                signed.contentDigest(), webhookId) == 1;
    }

    @Override
    public int failExhausted(int maxAttempts, int limit) {
        return jdbcTemplate.update(FAIL_EXHAUSTED, maxAttempts, limit);
    }

    @Override
    public List<String> claimDue(OffsetDateTime now, OffsetDateTime leaseUntil, int maxAttempts, int limit) {
        return jdbcTemplate.queryForList(CLAIM_DUE, String.class, leaseUntil, now, maxAttempts, limit);
    }
}
