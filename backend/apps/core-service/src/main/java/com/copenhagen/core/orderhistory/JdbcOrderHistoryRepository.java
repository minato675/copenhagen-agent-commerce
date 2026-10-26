package com.copenhagen.core.orderhistory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.copenhagen.core.common.crypto.EncryptedPii;

@Repository
public class JdbcOrderHistoryRepository implements OrderHistoryRepository {

    private static final String SELECT_COLUMNS = """
            SELECT o.id, o.public_id, o.checkout_snapshot_id, o.status,
                   pt.payment_amount, pt.currency, pt.payment_label, o.created_at,
                   cs.recipient_name_encrypted, cs.recipient_phone_encrypted, cs.postal_code_encrypted,
                   cs.address_line1_encrypted, cs.address_line2_encrypted, cs.delivery_request_encrypted
            FROM orders o
            JOIN checkout_snapshots cs ON cs.id = o.checkout_snapshot_id
            JOIN payment_transactions pt ON pt.order_id = o.id AND pt.status = 'PAID'
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOrderHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OrderRow> findPage(long userId, String status, OrderCursor cursor, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append("WHERE o.user_id = ? ");
        List<Object> arguments = new ArrayList<>();
        arguments.add(userId);
        if (status != null) {
            sql.append("AND o.status = ? ");
            arguments.add(status);
        }
        if (cursor != null) {
            sql.append("AND (o.created_at < ? OR (o.created_at = ? AND o.id < ?)) ");
            Timestamp createdAt = Timestamp.from(cursor.createdAt());
            arguments.add(createdAt);
            arguments.add(createdAt);
            arguments.add(cursor.orderId());
        }
        sql.append("ORDER BY o.created_at DESC, o.id DESC LIMIT ?");
        arguments.add(limit);
        return jdbcTemplate.query(sql.toString(), JdbcOrderHistoryRepository::mapOrder, arguments.toArray());
    }

    @Override
    public Optional<OrderRow> findByPublicIdAndUserId(UUID publicId, long userId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    SELECT_COLUMNS + "WHERE o.public_id = ? AND o.user_id = ?",
                    JdbcOrderHistoryRepository::mapOrder,
                    publicId,
                    userId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public List<OrderItemRow> findItemsBySnapshotIds(List<Long> checkoutSnapshotIds) {
        if (checkoutSnapshotIds.isEmpty()) {
            return List.of();
        }
        String placeholders = checkoutSnapshotIds.stream().map(ignored -> "?")
                .collect(Collectors.joining(", "));
        // merchant_id는 주문서 단위라 checkout_snapshot_items에 없다. v2 응답이 상품 식별자를 내주려면
        // merchant_checkouts까지 두 단계를 거슬러야 한다.
        String sql = """
                SELECT csi.checkout_snapshot_id, mc.merchant_id,
                       csi.merchant_product_id, csi.merchant_variant_id,
                       csi.product_name, csi.image_url, csi.selected_options::text, csi.quantity
                FROM checkout_snapshot_items csi
                JOIN checkout_snapshots cs ON cs.id = csi.checkout_snapshot_id
                JOIN merchant_checkouts mc ON mc.id = cs.merchant_checkout_id
                WHERE csi.checkout_snapshot_id IN (%s)
                ORDER BY csi.checkout_snapshot_id ASC, csi.id ASC
                """.formatted(placeholders);
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new OrderItemRow(
                        resultSet.getLong("checkout_snapshot_id"),
                        resultSet.getLong("merchant_id"),
                        resultSet.getString("merchant_product_id"),
                        resultSet.getString("merchant_variant_id"),
                        resultSet.getString("product_name"),
                        resultSet.getString("image_url"),
                        resultSet.getString("selected_options"),
                        resultSet.getInt("quantity")),
                checkoutSnapshotIds.toArray());
    }

    private static OrderRow mapOrder(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new OrderRow(
                resultSet.getLong("id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getLong("checkout_snapshot_id"),
                resultSet.getString("status"),
                resultSet.getLong("payment_amount"),
                resultSet.getString("currency"),
                resultSet.getString("payment_label"),
                createdAt.toInstant(),
                EncryptedPii.of(resultSet.getBytes("recipient_name_encrypted")),
                EncryptedPii.of(resultSet.getBytes("recipient_phone_encrypted")),
                EncryptedPii.of(resultSet.getBytes("postal_code_encrypted")),
                EncryptedPii.of(resultSet.getBytes("address_line1_encrypted")),
                EncryptedPii.of(resultSet.getBytes("address_line2_encrypted")),
                EncryptedPii.of(resultSet.getBytes("delivery_request_encrypted")));
    }
}
