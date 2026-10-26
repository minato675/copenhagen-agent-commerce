package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcNotificationOutboxRepository implements NotificationOutboxRepository {

    private static final String INSERT_APPROVAL_SQL = """
            INSERT INTO pay_notification_outbox
                (id, event_id, event_type, user_id, approval_id, payload,
                 status, attempt_count, next_run_at)
            VALUES (?, ?, 'APPROVAL_REQUESTED', ?, ?, ?::jsonb, 'PENDING', 0, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String INSERT_PAYMENT_SQL = """
            INSERT INTO pay_notification_outbox
                (id, event_id, event_type, user_id, approval_id, payment_id,
                 payment_status, payload, status, attempt_count, next_run_at)
            SELECT ?, ?, 'PAYMENT_RESULT', session.user_id, ?, ?, ?, ?::jsonb,
                   'PENDING', 0, ?
            FROM authorization_sessions session
            WHERE session.id = ?
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String CLAIM_EVENT_SQL = """
            WITH candidate AS (
                SELECT id
                FROM pay_notification_outbox
                WHERE ((status IN ('PENDING', 'FAILED')
                        AND next_run_at <= CAST(? AS TIMESTAMPTZ))
                   OR (status = 'RUNNING'
                        AND lease_expires_at <= CAST(? AS TIMESTAMPTZ)))
                ORDER BY next_run_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE pay_notification_outbox event
            SET status = 'RUNNING',
                attempt_count = event.attempt_count + 1,
                lease_expires_at = CAST(? AS TIMESTAMPTZ),
                last_error = NULL
            FROM candidate
            WHERE event.id = candidate.id
            RETURNING event.id, event.event_id, event.event_type, event.user_id,
                      event.approval_id, event.payment_id, event.payment_status,
                      event.payload ->> 'requestId' AS request_id,
                      event.payload::text AS payload_json, event.status,
                      event.attempt_count, event.next_run_at, event.lease_expires_at,
                      event.sent_at, event.last_error, event.created_at
            """;

    private static final String ENSURE_DELIVERY_SQL = """
            INSERT INTO pay_notification_deliveries
                (id, event_id, fid, status, attempt_count, next_run_at)
            VALUES (?, ?, ?, 'PENDING', 0, ?)
            ON CONFLICT (event_id, fid) DO NOTHING
            """;

    private static final String PRUNE_INACTIVE_DELIVERIES_SQL = """
            UPDATE pay_notification_deliveries delivery
            SET status = 'EXHAUSTED', lease_expires_at = NULL,
                last_error = 'FID_NOT_ACTIVE'
            WHERE delivery.event_id = ?
              AND delivery.status IN ('PENDING', 'FAILED', 'SENDING')
              AND NOT EXISTS (
                  SELECT 1
                  FROM pay_notification_outbox event
                  JOIN pay_notification_installations installation
                    ON installation.user_id = event.user_id
                   AND installation.fid = delivery.fid
                   AND installation.invalidated_at IS NULL
                  WHERE event.event_id = delivery.event_id
              )
            """;

    private static final String CLAIM_DELIVERY_SQL = """
            WITH candidate AS (
                SELECT delivery.id
                FROM pay_notification_deliveries delivery
                JOIN pay_notification_outbox event
                  ON event.event_id = delivery.event_id
                JOIN pay_notification_installations installation
                  ON installation.user_id = event.user_id
                 AND installation.fid = delivery.fid
                 AND installation.invalidated_at IS NULL
                WHERE delivery.event_id = ?
                  AND ((delivery.status IN ('PENDING', 'FAILED')
                        AND delivery.next_run_at <= CAST(? AS TIMESTAMPTZ))
                   OR (delivery.status = 'SENDING'
                        AND delivery.lease_expires_at <= CAST(? AS TIMESTAMPTZ)))
                ORDER BY delivery.next_run_at, delivery.id
                FOR UPDATE OF delivery SKIP LOCKED
                LIMIT 1
            )
            UPDATE pay_notification_deliveries delivery
            SET status = 'SENDING',
                attempt_count = delivery.attempt_count + 1,
                lease_expires_at = CAST(? AS TIMESTAMPTZ),
                last_error = NULL
            FROM candidate
            WHERE delivery.id = candidate.id
            RETURNING delivery.id, delivery.event_id, delivery.fid,
                      delivery.attempt_count, delivery.lease_expires_at
            """;

    private static final String MARK_DELIVERY_SENT_SQL = """
            UPDATE pay_notification_deliveries
            SET status = 'SENT', sent_at = ?, lease_expires_at = NULL, last_error = NULL
            WHERE id = ? AND status = 'SENDING' AND attempt_count = ?
              AND lease_expires_at = ?
            """;

    private static final String MARK_DELIVERY_FAILED_SQL = """
            UPDATE pay_notification_deliveries
            SET status = ?, next_run_at = ?, lease_expires_at = NULL, last_error = ?
            WHERE id = ? AND status = 'SENDING' AND attempt_count = ?
              AND lease_expires_at = ?
            """;

    private static final String SUMMARIZE_DELIVERIES_SQL = """
            SELECT EXISTS (
                       SELECT 1 FROM pay_notification_deliveries
                       WHERE event_id = ? AND status IN ('PENDING', 'FAILED', 'SENDING')
                   ) AS has_retryable,
                   EXISTS (
                       SELECT 1 FROM pay_notification_deliveries
                       WHERE event_id = ? AND status = 'EXHAUSTED'
                   ) AS has_exhausted,
                   (
                       SELECT MIN(next_run_at)
                       FROM pay_notification_deliveries
                       WHERE event_id = ? AND status IN ('PENDING', 'FAILED')
                   ) AS next_run_at
            """;

    private static final String MARK_EVENT_SENT_SQL = """
            UPDATE pay_notification_outbox
            SET status = 'SENT', sent_at = ?, lease_expires_at = NULL, last_error = NULL
            WHERE id = ? AND status = 'RUNNING' AND attempt_count = ?
              AND lease_expires_at = ?
            """;

    private static final String MARK_EVENT_RETRY_SQL = """
            UPDATE pay_notification_outbox
            SET status = CASE WHEN attempt_count >= 10 THEN 'EXHAUSTED' ELSE 'FAILED' END,
                next_run_at = ?, lease_expires_at = NULL, last_error = ?
            WHERE id = ? AND status = 'RUNNING' AND attempt_count = ?
              AND lease_expires_at = ?
            """;

    private static final RowMapper<NotificationOutboxEvent> EVENT_ROW_MAPPER =
            JdbcNotificationOutboxRepository::mapEvent;
    private static final RowMapper<NotificationDelivery> DELIVERY_ROW_MAPPER =
            JdbcNotificationOutboxRepository::mapDelivery;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcNotificationOutboxRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insertApprovalRequested(String approvalId, String userId, Instant now) {
        String eventId = NotificationEventIds.approvalRequested(approvalId);
        jdbcTemplate.update(
                INSERT_APPROVAL_SQL,
                eventId,
                eventId,
                userId,
                approvalId,
                payload(new NotificationMessage(
                        eventId,
                        NotificationEventType.APPROVAL_REQUESTED,
                        approvalId,
                        null,
                        null,
                        "OPEN_APPROVAL")),
                Timestamp.from(now));
    }

    @Override
    public void insertPaymentResult(
            String paymentId,
            String approvalId,
            PaymentStatus status,
            Instant now) {
        if (status != PaymentStatus.SUCCESS
                && status != PaymentStatus.FAILED
                && status != PaymentStatus.UNKNOWN) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }
        String eventId = NotificationEventIds.paymentResult(paymentId, status.name());
        jdbcTemplate.update(
                INSERT_PAYMENT_SQL,
                eventId,
                eventId,
                approvalId,
                paymentId,
                status.name(),
                payload(new NotificationMessage(
                        eventId,
                        NotificationEventType.PAYMENT_RESULT,
                        approvalId,
                        paymentId,
                        status.name(),
                        NotificationMessage.nextAction(status))),
                Timestamp.from(now),
                approvalId);
    }

    @Override
    public Optional<NotificationOutboxEvent> claimNext(Instant now, Instant leaseExpiresAt) {
        Timestamp nowTimestamp = Timestamp.from(now);
        return jdbcTemplate.query(
                        CLAIM_EVENT_SQL,
                        EVENT_ROW_MAPPER,
                        nowTimestamp,
                        nowTimestamp,
                        Timestamp.from(leaseExpiresAt))
                .stream()
                .findFirst();
    }

    @Override
    public void ensureDeliveries(String eventId, List<String> fids, Instant nextRunAt) {
        if (fids.isEmpty()) {
            return;
        }
        Timestamp timestamp = Timestamp.from(nextRunAt);
        jdbcTemplate.batchUpdate(
                ENSURE_DELIVERY_SQL,
                fids,
                fids.size(),
                (statement, fid) -> {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, eventId);
                    statement.setString(3, fid);
                    statement.setTimestamp(4, timestamp);
                });
    }

    @Override
    public void pruneInactiveDeliveries(String eventId) {
        jdbcTemplate.update(PRUNE_INACTIVE_DELIVERIES_SQL, eventId);
    }

    @Override
    public Optional<NotificationDelivery> claimNextDelivery(
            String eventId,
            Instant now,
            Instant leaseExpiresAt) {
        Timestamp nowTimestamp = Timestamp.from(now);
        return jdbcTemplate.query(
                        CLAIM_DELIVERY_SQL,
                        DELIVERY_ROW_MAPPER,
                        eventId,
                        nowTimestamp,
                        nowTimestamp,
                        Timestamp.from(leaseExpiresAt))
                .stream()
                .findFirst();
    }

    @Override
    public boolean markDeliverySent(NotificationDelivery delivery, Instant sentAt) {
        return jdbcTemplate.update(
                MARK_DELIVERY_SENT_SQL,
                Timestamp.from(sentAt),
                delivery.id(),
                delivery.attemptCount(),
                Timestamp.from(delivery.leaseExpiresAt())) == 1;
    }

    @Override
    public boolean markDeliveryFailed(
            NotificationDelivery delivery,
            Instant nextRunAt,
            String errorCode,
            boolean exhausted) {
        return jdbcTemplate.update(
                MARK_DELIVERY_FAILED_SQL,
                exhausted ? NotificationOutboxStatus.EXHAUSTED.name()
                        : NotificationOutboxStatus.FAILED.name(),
                Timestamp.from(nextRunAt),
                errorCode,
                delivery.id(),
                delivery.attemptCount(),
                Timestamp.from(delivery.leaseExpiresAt())) == 1;
    }

    @Override
    public DeliverySummary summarizeDeliveries(String eventId) {
        return jdbcTemplate.queryForObject(
                SUMMARIZE_DELIVERIES_SQL,
                (resultSet, rowNumber) -> new DeliverySummary(
                        resultSet.getBoolean("has_retryable"),
                        resultSet.getBoolean("has_exhausted"),
                        getInstant(resultSet, "next_run_at")),
                eventId,
                eventId,
                eventId);
    }

    @Override
    public boolean markEventSent(NotificationOutboxEvent event, Instant sentAt) {
        return jdbcTemplate.update(
                MARK_EVENT_SENT_SQL,
                Timestamp.from(sentAt),
                event.id(),
                event.attemptCount(),
                Timestamp.from(event.leaseExpiresAt())) == 1;
    }

    @Override
    public boolean markEventRetry(
            NotificationOutboxEvent event,
            Instant nextRunAt,
            String errorCode) {
        return jdbcTemplate.update(
                MARK_EVENT_RETRY_SQL,
                Timestamp.from(nextRunAt),
                errorCode,
                event.id(),
                event.attemptCount(),
                Timestamp.from(event.leaseExpiresAt())) == 1;
    }

    private String payload(NotificationMessage message) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("eventId", message.eventId());
            values.put("type", message.type().name());
            values.put("approvalId", message.approvalId());
            putIfPresent(values, "requestId", PayLogContext.get(PayLogContext.REQUEST_ID));
            putIfPresent(values, "paymentId", message.paymentId());
            putIfPresent(values, "status", message.status());
            values.put("nextAction", message.nextAction());
            return objectMapper.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    PayErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    private void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private static NotificationOutboxEvent mapEvent(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        String paymentStatus = resultSet.getString("payment_status");
        return new NotificationOutboxEvent(
                resultSet.getString("id"),
                resultSet.getString("event_id"),
                NotificationEventType.valueOf(resultSet.getString("event_type")),
                resultSet.getString("user_id"),
                resultSet.getString("request_id"),
                resultSet.getString("approval_id"),
                resultSet.getString("payment_id"),
                paymentStatus == null ? null : PaymentStatus.valueOf(paymentStatus),
                resultSet.getString("payload_json"),
                NotificationOutboxStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                getInstant(resultSet, "next_run_at"),
                getInstant(resultSet, "lease_expires_at"),
                getInstant(resultSet, "sent_at"),
                resultSet.getString("last_error"),
                getInstant(resultSet, "created_at"));
    }

    private static NotificationDelivery mapDelivery(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        return new NotificationDelivery(
                resultSet.getString("id"),
                resultSet.getString("event_id"),
                resultSet.getString("fid"),
                resultSet.getInt("attempt_count"),
                getInstant(resultSet, "lease_expires_at"));
    }

    private static Instant getInstant(ResultSet resultSet, String columnName) throws SQLException {
        OffsetDateTime value = resultSet.getObject(columnName, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
