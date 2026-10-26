package com.copenhagen.merchantmock.order;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** orders 와 order_items 를 읽고 쓴다. JdbcTemplate 만 쓴다(DESIGN.md 4절). */
@Repository
public class JdbcOrderRepository implements OrderRepository {

    private static final String INSERT_ORDER = """
            INSERT INTO orders
                (id, merchant_id, checkout_session_id, status, total_amount, shipping_fee_amount,
                 currency, raw_order_json,
                 recipient_name, recipient_phone, postal_code, address_line1, address_line2)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ITEM = """
            INSERT INTO order_items
                (id, order_id, line_no, product_id, sku, title, unit_amount, quantity, line_amount)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ORDER = """
            SELECT id, merchant_id, checkout_session_id, status, total_amount, shipping_fee_amount,
                   currency, raw_order_json::text,
                   recipient_name, recipient_phone, postal_code, address_line1, address_line2,
                   version, updated_at
              FROM orders
             WHERE id = ?
            """;

    private static final String SELECT_BY_CHECKOUT = """
            SELECT id, merchant_id, checkout_session_id, status, total_amount, shipping_fee_amount,
                   currency, raw_order_json::text,
                   recipient_name, recipient_phone, postal_code, address_line1, address_line2,
                   version, updated_at
              FROM orders
             WHERE checkout_session_id = ?
            """;

    private static final String SELECT_ITEMS = """
            SELECT id, order_id, line_no, product_id, sku, title, unit_amount, quantity, line_amount
              FROM order_items
             WHERE order_id = ?
             ORDER BY line_no
            """;

    private static final RowMapper<Order> ORDER_MAPPER = (rs, rowNum) -> new Order(
            rs.getString("id"),
            rs.getString("merchant_id"),
            rs.getString("checkout_session_id"),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getLong("total_amount"),
            rs.getLong("shipping_fee_amount"),
            rs.getString("currency"),
            rs.getString("raw_order_json"),
            rs.getString("recipient_name"),
            rs.getString("recipient_phone"),
            rs.getString("postal_code"),
            rs.getString("address_line1"),
            rs.getString("address_line2"),
            rs.getLong("version"),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final RowMapper<OrderItem> ITEM_MAPPER = (rs, rowNum) -> new OrderItem(
            rs.getString("id"),
            rs.getString("order_id"),
            rs.getInt("line_no"),
            rs.getString("product_id"),
            rs.getString("sku"),
            rs.getString("title"),
            rs.getLong("unit_amount"),
            rs.getInt("quantity"),
            rs.getLong("line_amount"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 주문과 항목은 한 트랜잭션이다. 항목 없는 주문이 남으면 무엇을 팔았는지 알 수 없게 된다.
     */
    @Override
    @Transactional
    public boolean insert(Order order, List<OrderItem> items) {
        try {
            jdbcTemplate.update(INSERT_ORDER,
                    order.id(), order.merchantId(), order.checkoutSessionId(), order.status().name(),
                    order.totalAmount(), order.shippingFeeAmount(),
                    order.currency(), order.rawOrderJson(), order.recipientName(), order.recipientPhone(),
                    order.postalCode(), order.addressLine1(), order.addressLine2());
        } catch (DuplicateKeyException exception) {
            return false;
        }
        for (OrderItem item : items) {
            jdbcTemplate.update(INSERT_ITEM,
                    item.id(), item.orderId(), item.lineNo(), item.productId(), item.sku(),
                    item.title(), item.unitAmount(), item.quantity(), item.lineAmount());
        }
        return true;
    }

    @Override
    public Optional<Order> findById(String id) {
        return jdbcTemplate.query(SELECT_ORDER, ORDER_MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Order> findByCheckoutSessionId(String checkoutSessionId) {
        return jdbcTemplate.query(SELECT_BY_CHECKOUT, ORDER_MAPPER, checkoutSessionId).stream().findFirst();
    }

    @Override
    public List<OrderItem> findItems(String orderId) {
        return jdbcTemplate.query(SELECT_ITEMS, ITEM_MAPPER, orderId);
    }
}
