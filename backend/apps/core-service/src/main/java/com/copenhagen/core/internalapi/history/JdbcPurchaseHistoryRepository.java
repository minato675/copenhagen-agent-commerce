package com.copenhagen.core.internalapi.history;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 결제 완료 주문의 스냅샷 항목을 상품 단위로 묶는다(설계서 §4.3).
 *
 * <p>{@code payment_transactions}는 JOIN이 아니라 {@code EXISTS}로 건다. 한 주문에 PAID 행이 둘 이상이면
 * JOIN은 줄을 복제해 {@code SUM(quantity)}를 부풀린다({@code COUNT(DISTINCT order_id)}만 안전하다).
 * 주문 단위로만 읽는 {@code JdbcOrderHistoryRepository}와 달리 여기서는 합계를 낸다.
 */
@Repository
public class JdbcPurchaseHistoryRepository implements PurchaseHistoryRepository {

    private static final String SELECT = """
            WITH paid AS (
                SELECT o.id AS order_id, o.created_at, mc.merchant_id, csi.id AS item_id,
                       csi.merchant_product_id, csi.merchant_variant_id, csi.product_name,
                       csi.selected_options::text AS selected_options,
                       csi.quantity, csi.unit_price_amount, csi.currency
                FROM orders o
                JOIN checkout_snapshots cs ON cs.id = o.checkout_snapshot_id
                JOIN merchant_checkouts mc ON mc.id = cs.merchant_checkout_id
                JOIN checkout_snapshot_items csi ON csi.checkout_snapshot_id = cs.id
                WHERE o.user_id = ?
                  AND o.status NOT IN ('CANCELLED', 'FAILED')
                  AND o.created_at >= ?
                  AND EXISTS (SELECT 1 FROM payment_transactions pt
                              WHERE pt.order_id = o.id AND pt.status = 'PAID')
            ),
            aggregated AS (
                SELECT merchant_id, merchant_product_id,
                       COUNT(DISTINCT order_id) AS purchase_count,
                       SUM(quantity) AS total_quantity,
                       MAX(created_at) AS last_purchased_at
                FROM paid
                GROUP BY merchant_id, merchant_product_id
            ),
            latest AS (
                SELECT DISTINCT ON (merchant_id, merchant_product_id)
                       merchant_id, merchant_product_id, merchant_variant_id, product_name,
                       selected_options, unit_price_amount, currency
                FROM paid
                ORDER BY merchant_id, merchant_product_id, created_at DESC, order_id DESC, item_id DESC
            )
            SELECT a.merchant_id, m.merchant_name, a.merchant_product_id, l.merchant_variant_id,
                   l.product_name, l.selected_options, a.purchase_count, a.total_quantity,
                   l.unit_price_amount, l.currency, a.last_purchased_at
            FROM aggregated a
            JOIN latest l ON l.merchant_id = a.merchant_id
                AND l.merchant_product_id = a.merchant_product_id
            JOIN merchants m ON m.id = a.merchant_id
            """;

    private static final String CURSOR_CONDITION = """
            WHERE (a.last_purchased_at < ?
                   OR (a.last_purchased_at = ? AND a.merchant_id > ?)
                   OR (a.last_purchased_at = ? AND a.merchant_id = ? AND a.merchant_product_id > ?))
            """;

    private static final String ORDER_BY = """
            ORDER BY a.last_purchased_at DESC, a.merchant_id ASC, a.merchant_product_id ASC
            LIMIT ?""";

    private final JdbcTemplate jdbcTemplate;

    public JdbcPurchaseHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<PurchaseHistoryRow> findPage(
            long userId, Instant periodStart, PurchaseHistoryCursor cursor, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> arguments = new ArrayList<>();
        arguments.add(userId);
        arguments.add(Timestamp.from(periodStart));
        if (cursor != null) {
            sql.append(CURSOR_CONDITION);
            Timestamp lastPurchasedAt = Timestamp.from(
                    Instant.ofEpochSecond(cursor.epochSecond(), cursor.nano()));
            arguments.add(lastPurchasedAt);
            arguments.add(lastPurchasedAt);
            arguments.add(cursor.merchantRefId());
            arguments.add(lastPurchasedAt);
            arguments.add(cursor.merchantRefId());
            arguments.add(cursor.merchantProductId());
        }
        sql.append(ORDER_BY);
        arguments.add(limit);
        return jdbcTemplate.query(sql.toString(), JdbcPurchaseHistoryRepository::mapRow, arguments.toArray());
    }

    private static PurchaseHistoryRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PurchaseHistoryRow(
                resultSet.getLong("merchant_id"),
                resultSet.getString("merchant_name"),
                resultSet.getString("merchant_product_id"),
                resultSet.getString("merchant_variant_id"),
                resultSet.getString("product_name"),
                resultSet.getString("selected_options"),
                resultSet.getInt("purchase_count"),
                resultSet.getLong("total_quantity"),
                resultSet.getLong("unit_price_amount"),
                resultSet.getString("currency"),
                resultSet.getTimestamp("last_purchased_at").toInstant());
    }
}
