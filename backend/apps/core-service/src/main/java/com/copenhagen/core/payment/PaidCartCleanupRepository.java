package com.copenhagen.core.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Serialize all merchant completions on the purchase before touching its cart. */
@Repository
public class PaidCartCleanupRepository {
    private final JdbcTemplate jdbc;

    public PaidCartCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long lockPurchaseForSnapshot(long snapshotId) {
        return jdbc.queryForObject("""
                SELECT pr.id FROM purchase_requests pr
                JOIN merchant_checkouts mc ON mc.purchase_request_id = pr.id
                JOIN checkout_snapshots cs ON cs.merchant_checkout_id = mc.id
                WHERE cs.id = ? FOR UPDATE OF pr
                """, Long.class, snapshotId);
    }

    public List<Long> pending() {
        return jdbc.queryForList("""
                SELECT pr.id FROM purchase_requests pr WHERE pr.cart_cleaned_at IS NULL
                AND EXISTS (SELECT 1 FROM merchant_checkouts mc WHERE mc.purchase_request_id = pr.id)
                AND NOT EXISTS (SELECT 1 FROM merchant_checkouts mc
                    WHERE mc.purchase_request_id = pr.id AND mc.status <> 'COMPLETED')
                ORDER BY pr.id LIMIT 100
                """, Long.class);
    }

    @Transactional
    public void cleanup(long purchaseId, Instant now) {
        List<Long> carts = jdbc.query("""
                SELECT cart_id, cart_cleaned_at FROM purchase_requests WHERE id = ? FOR UPDATE
                """, (row, index) -> row.getTimestamp("cart_cleaned_at") == null ? row.getLong("cart_id") : null,
                purchaseId);
        if (carts.isEmpty() || carts.getFirst() == null) {
            return;
        }
        long cartId = carts.getFirst();
        Boolean ready = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM merchant_checkouts WHERE purchase_request_id = ?)
                AND NOT EXISTS (SELECT 1 FROM merchant_checkouts
                    WHERE purchase_request_id = ? AND status <> 'COMPLETED')
                """, Boolean.class, purchaseId, purchaseId);
        if (!Boolean.TRUE.equals(ready)) {
            return;
        }
        jdbc.queryForObject("SELECT id FROM carts WHERE id = ? FOR UPDATE", Long.class, cartId);
        // Legacy snapshots lack line identity. Only an unchanged cart version can prove membership.
        List<Line> lines = jdbc.query("""
                SELECT ci.id, ci.quantity AS current_quantity, SUM(si.quantity) AS purchased_quantity
                FROM purchase_requests pr
                JOIN carts c ON c.id = pr.cart_id
                JOIN merchant_checkouts mc ON mc.purchase_request_id = pr.id
                JOIN checkout_snapshots cs ON cs.merchant_checkout_id = mc.id
                JOIN checkout_snapshot_items si ON si.checkout_snapshot_id = cs.id
                JOIN cart_items ci ON ci.cart_id = c.id AND ci.merchant_id = mc.merchant_id
                    AND ci.merchant_product_id = si.merchant_product_id
                    AND ci.merchant_variant_id = si.merchant_variant_id
                    AND (ci.public_id = si.cart_item_public_id
                        OR (si.cart_item_public_id IS NULL AND c.version = pr.cart_version))
                WHERE pr.id = ?
                GROUP BY ci.id, ci.quantity
                """, (row, index) -> new Line(row.getLong("id"), row.getInt("current_quantity"),
                        row.getLong("purchased_quantity")), purchaseId);
        for (Line line : lines) {
            if (line.current() <= line.purchased()) {
                jdbc.update("DELETE FROM cart_items WHERE id = ?", line.id());
            } else {
                jdbc.update("UPDATE cart_items SET quantity = ?, updated_at = ? WHERE id = ?",
                        line.current() - line.purchased(), Timestamp.from(now), line.id());
            }
        }
        if (!lines.isEmpty()) {
            jdbc.update("UPDATE carts SET version = version + 1, updated_at = ? WHERE id = ?",
                    Timestamp.from(now), cartId);
        }
        jdbc.update("UPDATE purchase_requests SET cart_cleaned_at = ? WHERE id = ?", Timestamp.from(now), purchaseId);
    }

    private record Line(long id, int current, long purchased) { }
}
