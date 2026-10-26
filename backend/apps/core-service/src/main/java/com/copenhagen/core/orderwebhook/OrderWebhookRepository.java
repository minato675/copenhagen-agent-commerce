package com.copenhagen.core.orderwebhook;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

@Repository
public class OrderWebhookRepository {

    private final JdbcTemplate jdbc;

    public OrderWebhookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void apply(long merchantId, long snapshotId, String orderId, String webhookId, String digest,
            String status, Instant merchantUpdatedAt, Instant now) {
        jdbc.queryForObject("SELECT id FROM checkout_snapshots WHERE id = ? FOR UPDATE", Long.class, snapshotId);
        int inserted = jdbc.update("""
                INSERT INTO order_webhook_receipts (merchant_id, webhook_id, payload_digest, received_at)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, merchantId, webhookId, digest, Timestamp.from(now));
        if (inserted == 0) {
            String recorded = jdbc.queryForObject("""
                    SELECT payload_digest FROM order_webhook_receipts WHERE merchant_id = ? AND webhook_id = ?
                    """, String.class, merchantId, webhookId);
            if (!digest.equals(recorded)) {
                throw new CoreException(CoreErrorCode.WEBHOOK_ID_CONFLICT);
            }
            return;
        }
        List<StoredOrder> orders = jdbc.query("""
                SELECT id, status, merchant_updated_at FROM orders
                WHERE checkout_snapshot_id = ? AND merchant_order_id = ?
                """, (row, index) -> new StoredOrder(row.getLong("id"), row.getString("status"),
                        row.getTimestamp("merchant_updated_at") == null
                                ? null : row.getTimestamp("merchant_updated_at").toInstant()), snapshotId, orderId);
        if (orders.isEmpty()) {
            throw new CoreException(CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE);
        }
        StoredOrder order = orders.get(0);
        if (order.updatedAt() != null && merchantUpdatedAt.isBefore(order.updatedAt())
                || rank(status) < rank(order.status())) {
            return;
        }
        jdbc.update("""
                UPDATE orders SET status = ?, merchant_updated_at = ?, updated_at = ?,
                    confirmed_at = CASE WHEN ? = 'CONFIRMED' THEN COALESCE(confirmed_at, ?) ELSE confirmed_at END,
                    cancelled_at = CASE WHEN ? = 'CANCELLED' THEN COALESCE(cancelled_at, ?) ELSE cancelled_at END
                WHERE id = ?
                """, status, Timestamp.from(merchantUpdatedAt), Timestamp.from(now), status,
                Timestamp.from(merchantUpdatedAt), status, Timestamp.from(merchantUpdatedAt), order.id());
    }

    public boolean processed(long merchantId, String webhookId, String digest) {
        List<String> existing = jdbc.queryForList(
                "SELECT payload_digest FROM order_webhook_receipts WHERE merchant_id = ? AND webhook_id = ?",
                String.class, merchantId, webhookId);
        if (existing.isEmpty()) {
            return false;
        }
        if (!digest.equals(existing.get(0))) {
            throw new CoreException(CoreErrorCode.WEBHOOK_ID_CONFLICT);
        }
        return true;
    }

    private static int rank(String status) {
        return switch (status) {
            case "CREATED" -> 0;
            case "CONFIRMED" -> 1;
            case "FULFILLING" -> 2;
            case "SHIPPED" -> 3;
            case "DELIVERED" -> 4;
            case "CANCELLED" -> 5;
            default -> -1;
        };
    }

    private record StoredOrder(long id, String status, Instant updatedAt) {
    }
}
