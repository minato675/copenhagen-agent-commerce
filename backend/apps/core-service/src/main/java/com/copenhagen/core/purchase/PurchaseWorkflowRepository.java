package com.copenhagen.core.purchase;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

@Repository
public class PurchaseWorkflowRepository {
    private static final String SELECT = """
            SELECT p.*, c.public_id AS cart_public_id, u.public_id AS user_public_id,
                a.public_id AS address_public_id
            FROM purchase_requests p JOIN carts c ON c.id = p.cart_id
            JOIN users u ON u.id = p.user_id LEFT JOIN addresses a ON a.id = p.shipping_address_id
            """;
    private final JdbcTemplate jdbc;

    public PurchaseWorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Row require(UUID id, long userId) {
        return query(" WHERE p.public_id = ? AND p.user_id = ? AND p.request_channel = 'MCP'", id, userId)
                .stream().findFirst().orElseThrow(() -> new CoreException(CoreErrorCode.ORDER_NOT_FOUND));
    }

    public List<Row> recent(long userId) {
        return query(" WHERE p.user_id = ? AND p.request_channel = 'MCP' ORDER BY p.created_at DESC LIMIT 50", userId);
    }

    public List<Row> due(Instant now) {
        return query("""
                 WHERE p.request_channel = 'MCP' AND p.status IN ('PENDING_ADDRESS', 'CREATING_CHECKOUTS')
                 AND (p.workflow_retry_at IS NULL OR p.workflow_retry_at <= ?)
                 AND (p.workflow_lease_until IS NULL OR p.workflow_lease_until <= ?)
                 ORDER BY p.created_at LIMIT 20
                """, Timestamp.from(now), Timestamp.from(now));
    }

    public boolean claim(long id, Instant now) {
        return jdbc.update("""
                UPDATE purchase_requests SET workflow_lease_until = ?
                WHERE id = ? AND status IN ('PENDING_ADDRESS', 'CREATING_CHECKOUTS')
                AND (workflow_lease_until IS NULL OR workflow_lease_until <= ?)
                """, Timestamp.from(now.plusSeconds(120)), id, Timestamp.from(now)) == 1;
    }

    @Transactional
    public void resume(UUID publicId, long userId, UUID addressId, int version, UUID key, Instant now) {
        Row row = require(publicId, userId);
        // 같은 사용자가 서로 다른 구매에 같은 멱등키를 동시에 써도 키 검사가 직렬화된다.
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE", Long.class, userId);
        jdbc.queryForObject("SELECT id FROM purchase_requests WHERE id = ? FOR UPDATE", Long.class, row.id());
        row = require(publicId, userId);
        List<Resume> previous = jdbc.query("""
                SELECT purchase_request_id, address_id, cart_version FROM purchase_resume_commands
                WHERE user_id = ? AND idempotency_key = ?
                """, (r, i) -> new Resume(r.getLong(1), r.getObject(2, UUID.class), r.getInt(3)), userId, key);
        if (!previous.isEmpty()) {
            if (!previous.get(0).equals(new Resume(row.id(), addressId, version))) {
                throw new CoreException(CoreErrorCode.INVALID_REQUEST);
            }
            return;
        }
        if (row.cartVersion() != version) {
            throw new CoreException(CoreErrorCode.CART_VERSION_CONFLICT);
        }
        if (row.expiresAt() == null || !now.isBefore(row.expiresAt())) {
            throw new CoreException(CoreErrorCode.PURCHASE_REQUEST_EXPIRED);
        }
        if (!"PENDING_ADDRESS".equals(row.status())) {
            if (!addressId.equals(row.addressId())) {
                throw new CoreException(CoreErrorCode.INVALID_REQUEST);
            }
            jdbc.update("""
                    INSERT INTO purchase_resume_commands
                        (user_id, idempotency_key, purchase_request_id, address_id, cart_version)
                    VALUES (?, ?, ?, ?, ?)
                    """, userId, key, row.id(), addressId, version);
            return;
        }
        List<Long> addresses = jdbc.queryForList("""
                SELECT id FROM addresses WHERE public_id = ? AND user_id = ? AND deleted_at IS NULL
                """, Long.class, addressId, userId);
        if (addresses.isEmpty()) {
            throw new CoreException(CoreErrorCode.ADDRESS_NOT_FOUND);
        }
        int currentVersion = jdbc.queryForObject("SELECT version FROM carts WHERE id = ?", Integer.class, row.cartId());
        if (currentVersion != version) {
            throw new CoreException(CoreErrorCode.CART_VERSION_CONFLICT);
        }
        jdbc.update("""
                INSERT INTO purchase_resume_commands
                    (user_id, idempotency_key, purchase_request_id, address_id, cart_version)
                VALUES (?, ?, ?, ?, ?)
                """, userId, key, row.id(), addressId, version);
        jdbc.update("""
                UPDATE purchase_requests SET shipping_address_id = ?, status = 'CREATING_CHECKOUTS',
                    workflow_retry_at = NULL, updated_at = ? WHERE id = ?
                """, addresses.get(0), Timestamp.from(now), row.id());
    }

    public boolean hasCheckouts(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM merchant_checkouts WHERE purchase_request_id = ?)", Boolean.class, id));
    }

    public List<Checkout> checkouts(long id) {
        return jdbc.query("""
                SELECT mc.merchant_id, mc.payment_approval_id, mc.status, mc.failure_code,
                    cs.total_amount, cs.currency
                FROM merchant_checkouts mc JOIN checkout_snapshots cs ON cs.merchant_checkout_id = mc.id
                WHERE mc.purchase_request_id = ? ORDER BY mc.id
                """, (r, i) -> new Checkout(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getLong(5), r.getString(6)), id);
    }

    public void finish(long id, String status, String failureCode, Instant now) {
        jdbc.update("""
                UPDATE purchase_requests SET status = CASE
                    WHEN ? = 'PENDING_ADDRESS' AND shipping_address_id IS NOT NULL THEN 'CREATING_CHECKOUTS'
                    ELSE ? END, failure_code = ?, workflow_lease_until = NULL,
                    workflow_retry_at = ?, updated_at = ? WHERE id = ?
                """, status, status, failureCode, Timestamp.from(now.plusSeconds(30)), Timestamp.from(now), id);
    }

    public void notificationError(long id, String code) {
        jdbc.update("UPDATE purchase_requests SET notification_error = ? WHERE id = ?", code, id);
    }

    public void requireNewAddress(long id) {
        jdbc.update("""
                UPDATE purchase_requests SET shipping_address_id = NULL, status = 'PENDING_ADDRESS',
                    workflow_lease_until = NULL, workflow_retry_at = NULL WHERE id = ?
                """, id);
    }

    private List<Row> query(String suffix, Object... args) {
        return jdbc.query(SELECT + suffix, (r, i) -> new Row(r.getLong("id"),
                r.getObject("public_id", UUID.class), r.getLong("user_id"), r.getObject("user_public_id", UUID.class),
                r.getLong("cart_id"), r.getObject("cart_public_id", UUID.class), r.getInt("cart_version"),
                r.getLong("agent_connection_id"), r.getString("status"), r.getObject("address_public_id", UUID.class),
                Optional.ofNullable(r.getTimestamp("expires_at")).map(Timestamp::toInstant).orElse(null),
                r.getString("failure_code"), r.getString("notification_error")), args);
    }

    public record Row(long id, UUID publicId, long userId, UUID userPublicId, long cartId, UUID cartPublicId,
            int cartVersion, long connectionId, String status, UUID addressId, Instant expiresAt,
            String failureCode, String notificationError) {
    }

    public record Checkout(long merchantId, String approvalId, String status, String failureCode,
            long totalAmount, String currency) {
    }

    private record Resume(long purchaseId, UUID addressId, int version) {
    }
}
