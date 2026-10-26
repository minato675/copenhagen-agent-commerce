package com.copenhagen.pay.payment.notification;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.contract.internalapi.PurchaseNotificationStatus;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;

/** Core가 소유권·만료를 확인한 구매 요청의 알림만 서비스 인증으로 인계한다. */
@Service
public class PurchaseNotificationService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PurchaseNotificationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void create(UUID userId, UUID purchaseId) {
        String eventId = UUID.nameUUIDFromBytes(("purchase-handoff:" + purchaseId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        jdbc.update("""
                INSERT INTO pay_notification_outbox
                    (id, event_id, event_type, user_id, payload, status, next_run_at)
                VALUES (?, ?, 'PURCHASE_HANDOFF', ?, jsonb_build_object('purchaseRequestId', CAST(? AS TEXT)),
                        'PENDING', ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, eventId, userId.toString(), purchaseId.toString(), Timestamp.from(clock.instant()));
    }

    public List<PurchaseNotificationStatus> status(UUID userId, UUID purchaseId) {
        return jdbc.query("""
                SELECT n.event_id, n.status, n.last_error
                FROM pay_notification_outbox n
                LEFT JOIN authorization_sessions a ON a.id = n.approval_id
                WHERE n.user_id = ? AND
                    ((n.event_type = 'PURCHASE_HANDOFF' AND n.payload ->> 'purchaseRequestId' = ?)
                     OR (n.event_type = 'APPROVAL_REQUESTED' AND a.purchase_request_id = ?))
                ORDER BY n.created_at, n.event_id
                """, (row, index) -> new PurchaseNotificationStatus(
                        row.getString("event_id"), row.getString("status"), row.getString("last_error")),
                userId.toString(), purchaseId.toString(), purchaseId.toString());
    }

    @Transactional
    public void resend(UUID userId, UUID purchaseId, UUID key) {
        int inserted = jdbc.update("""
                INSERT INTO pay_notification_resends (user_id, idempotency_key, purchase_request_id, created_at)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, userId.toString(), key, purchaseId.toString(), Timestamp.from(clock.instant()));
        if (inserted == 0) {
            String original = jdbc.queryForObject("""
                    SELECT purchase_request_id FROM pay_notification_resends
                    WHERE user_id = ? AND idempotency_key = ?
                    """, String.class, userId.toString(), key);
            if (!purchaseId.toString().equals(original)) {
                throw new PayException(PayErrorCode.INVALID_REQUEST);
            }
            return;
        }
        // 실행 중인 lease는 건드리지 않는다. 이미 만료·승인된 세션의 승인 알림은 재전송하지 않는다.
        List<String> eventIds = jdbc.queryForList("""
                UPDATE pay_notification_outbox n
                SET status = 'PENDING', attempt_count = 0, next_run_at = ?, last_error = NULL, sent_at = NULL
                WHERE n.user_id = ? AND n.status <> 'RUNNING'
                  AND (n.sent_at IS NULL OR n.sent_at < ?)
                  AND ((n.event_type = 'PURCHASE_HANDOFF' AND n.payload ->> 'purchaseRequestId' = ?
                        AND NOT EXISTS (SELECT 1 FROM authorization_sessions a WHERE a.purchase_request_id = ?))
                    OR (n.event_type = 'APPROVAL_REQUESTED' AND EXISTS (
                        SELECT 1 FROM authorization_sessions a WHERE a.id = n.approval_id
                        AND a.purchase_request_id = ? AND a.status IN ('PENDING', 'PAYMENT_METHOD_REQUIRED')
                        AND a.expires_at > ?)))
                RETURNING n.event_id
                """, String.class, Timestamp.from(clock.instant()), userId.toString(),
                Timestamp.from(clock.instant().minusSeconds(60)), purchaseId.toString(), purchaseId.toString(),
                purchaseId.toString(), Timestamp.from(clock.instant()));
        for (String eventId : eventIds) {
            jdbc.update("""
                    UPDATE pay_notification_deliveries SET status = 'PENDING', attempt_count = 0,
                        next_run_at = ?, last_error = NULL, sent_at = NULL, lease_expires_at = NULL
                    WHERE event_id = ?
                    """, Timestamp.from(clock.instant()), eventId);
        }
    }
}
