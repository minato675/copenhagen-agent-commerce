package com.copenhagen.core.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPaymentCompletionRepository implements PaymentCompletionRepository {

    private static final String INSERT_ORDER_SQL = """
            INSERT INTO orders
                (public_id, checkout_snapshot_id, user_id, merchant_order_id, status, ordered_at, created_at)
            VALUES (?, ?, ?, ?, 'CREATED', ?, ?)
            RETURNING id
            """;

    private static final String INSERT_PAID_TRANSACTION_SQL = """
            INSERT INTO payment_transactions
                (checkout_snapshot_id, order_id, authorization_session_id, pay_transaction_id, status,
                    payment_amount, currency, payment_label, approved_at, paid_at, created_at)
            VALUES (?, ?, ?, ?, 'PAID', ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_FAILED_TRANSACTION_SQL = """
            INSERT INTO payment_transactions
                (checkout_snapshot_id, authorization_session_id, pay_transaction_id, status,
                    payment_amount, currency, failure_code, failed_at, created_at)
            VALUES (?, ?, ?, 'FAILED', ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PaidCartCleanupRepository cartCleanup;

    public JdbcPaymentCompletionRepository(JdbcTemplate jdbcTemplate, PaidCartCleanupRepository cartCleanup) {
        this.jdbcTemplate = jdbcTemplate;
        this.cartCleanup = cartCleanup;
    }

    @Override
    @Transactional
    public void recordCompleted(
            long checkoutSnapshotId,
            long userId,
            String authorizationSessionId,
            String merchantOrderId,
            String payTransactionId,
            long amountMinor,
            String currency,
            String paymentLabel,
            Instant now) {
        long purchaseId = cartCleanup.lockPurchaseForSnapshot(checkoutSnapshotId);
        lockSnapshot(checkoutSnapshotId);
        List<Long> existingOrders = jdbcTemplate.queryForList(
                "SELECT id FROM orders WHERE checkout_snapshot_id = ?", Long.class, checkoutSnapshotId);
        Long orderId;
        if (existingOrders.isEmpty()) {
            orderId = jdbcTemplate.queryForObject(INSERT_ORDER_SQL, Long.class, UUID.randomUUID(),
                    checkoutSnapshotId, userId, merchantOrderId, Timestamp.from(now), Timestamp.from(now));
        } else {
            orderId = existingOrders.get(0);
            String existingMerchantOrderId = jdbcTemplate.queryForObject(
                    "SELECT merchant_order_id FROM orders WHERE id = ?", String.class, orderId);
            if (!merchantOrderId.equals(existingMerchantOrderId)) {
                throw new IllegalStateException("Merchant order identity changed");
            }
        }
        int updated = jdbcTemplate.update("""
                UPDATE payment_transactions
                SET order_id = ?, pay_transaction_id = ?, status = 'PAID', payment_amount = ?, currency = ?,
                    payment_label = COALESCE(?, payment_label), approved_at = COALESCE(approved_at, ?),
                    paid_at = COALESCE(paid_at, ?), failed_at = NULL, failure_code = NULL, updated_at = ?
                WHERE checkout_snapshot_id = ? AND authorization_session_id = ?
                """, orderId, payTransactionId, amountMinor, currency, paymentLabel, Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), checkoutSnapshotId, authorizationSessionId);
        if (updated == 0) {
            jdbcTemplate.update(INSERT_PAID_TRANSACTION_SQL, checkoutSnapshotId, orderId, authorizationSessionId,
                    payTransactionId, amountMinor, currency, paymentLabel, Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now));
        }
        jdbcTemplate.update("""
                UPDATE merchant_checkouts SET status = 'COMPLETED', completed_at = COALESCE(completed_at, ?),
                    updated_at = ?, completion_lease_until = NULL
                WHERE id = (SELECT merchant_checkout_id FROM checkout_snapshots WHERE id = ?)
                """, Timestamp.from(now), Timestamp.from(now), checkoutSnapshotId);
        cartCleanup.cleanup(purchaseId, now);
    }

    @Override
    @Transactional
    public void recordFailed(
            long checkoutSnapshotId,
            String authorizationSessionId,
            String payTransactionId,
            long amountMinor,
            String currency,
            String failureCode,
            Instant now) {
        lockSnapshot(checkoutSnapshotId);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_transactions
                WHERE checkout_snapshot_id = ? AND authorization_session_id = ?
                """, Integer.class, checkoutSnapshotId, authorizationSessionId);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(INSERT_FAILED_TRANSACTION_SQL, checkoutSnapshotId, authorizationSessionId,
                payTransactionId, amountMinor, currency, failureCode, Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                UPDATE merchant_checkouts SET status = 'FAILED', failure_code = ?, updated_at = ?,
                    completion_lease_until = NULL
                WHERE id = (SELECT merchant_checkout_id FROM checkout_snapshots WHERE id = ?)
                    AND status <> 'COMPLETED'
                """, failureCode, Timestamp.from(now), checkoutSnapshotId);
    }

    private void lockSnapshot(long checkoutSnapshotId) {
        // 모든 완료 경로가 같은 스냅샷을 먼저 잠가 폴링·웹훅·응답 재전송을 직렬화한다.
        jdbcTemplate.queryForObject("SELECT id FROM checkout_snapshots WHERE id = ? FOR UPDATE",
                Long.class, checkoutSnapshotId);
    }
}
