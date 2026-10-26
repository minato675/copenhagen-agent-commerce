package com.copenhagen.core.cart;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCartRepository implements CartRepository {

    private static final String FIND_ACTIVE_CART_SQL = """
            SELECT id, public_id, user_id, version
            FROM carts
            WHERE user_id = ? AND status = 'ACTIVE'
            """;

    private static final String INSERT_CART_SQL = """
            INSERT INTO carts (public_id, user_id, status, version, created_at)
            VALUES (?, ?, 'ACTIVE', 1, ?)
            RETURNING id
            """;

    private static final String BUMP_VERSION_SQL = """
            UPDATE carts
            SET version = version + 1, updated_at = ?
            WHERE id = ? AND version = ? AND status = 'ACTIVE'
            """;

    private static final String SELECT_ITEM_COLUMNS = """
            SELECT id, public_id, cart_id, merchant_id, merchant_product_id, merchant_variant_id,
                product_name, product_url, image_url, selected_options::text AS selected_options,
                quantity, unit_price_amount, currency
            FROM cart_items
            """;

    private static final String FIND_ITEM_BY_PRODUCT_VARIANT_SQL = SELECT_ITEM_COLUMNS + """
            WHERE cart_id = ? AND merchant_id = ? AND merchant_product_id = ? AND merchant_variant_id = ?
            """;

    private static final String FIND_ITEM_BY_PUBLIC_ID_SQL = SELECT_ITEM_COLUMNS + """
            WHERE public_id = ? AND cart_id = ?
            """;

    private static final String FIND_ITEMS_BY_CART_ID_SQL = SELECT_ITEM_COLUMNS + """
            WHERE cart_id = ?
            ORDER BY added_at, id
            """;

    private static final String INSERT_ITEM_SQL = """
            INSERT INTO cart_items
                (public_id, cart_id, merchant_id, merchant_product_id, merchant_variant_id, product_name,
                    product_url, image_url, selected_options, quantity, unit_price_amount, currency,
                    price_captured_at, added_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_ITEM_ON_MERGE_SQL = """
            UPDATE cart_items
            SET quantity = ?, product_name = ?, product_url = ?, image_url = ?, selected_options = ?::jsonb,
                unit_price_amount = ?, currency = ?, price_captured_at = ?, updated_at = ?
            WHERE id = ?
            """;

    private static final String UPDATE_ITEM_VARIANT_SQL = """
            UPDATE cart_items
            SET merchant_variant_id = ?, quantity = ?, product_name = ?, product_url = ?, image_url = ?,
                selected_options = ?::jsonb, unit_price_amount = ?, currency = ?, price_captured_at = ?,
                updated_at = ?
            WHERE id = ?
            """;

    private static final String DELETE_ITEM_BY_ID_SQL = """
            DELETE FROM cart_items
            WHERE id = ?
            """;

    private static final String UPDATE_ITEM_QUANTITY_SQL = """
            UPDATE cart_items
            SET quantity = ?, updated_at = ?
            WHERE public_id = ? AND cart_id = ?
            """;

    private static final String DELETE_ITEM_SQL = """
            DELETE FROM cart_items
            WHERE public_id = ? AND cart_id = ?
            """;

    private static final RowMapper<Cart> CART_ROW_MAPPER = JdbcCartRepository::mapCart;
    private static final RowMapper<CartItem> ITEM_ROW_MAPPER = JdbcCartRepository::mapItem;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCartRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Cart> findActiveByUserId(long userId) {
        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(FIND_ACTIVE_CART_SQL, CART_ROW_MAPPER, userId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Cart insertActiveCart(UUID publicId, long userId, Instant now) {
        Long id = jdbcTemplate.queryForObject(
                INSERT_CART_SQL, Long.class, publicId, userId, Timestamp.from(now));
        return new Cart(id == null ? 0L : id, publicId, userId, 1);
    }

    @Override
    public boolean bumpVersion(long cartId, int expectedVersion, Instant now) {
        return jdbcTemplate.update(BUMP_VERSION_SQL, Timestamp.from(now), cartId, expectedVersion) == 1;
    }

    @Override
    public Optional<CartItem> findItemByProductVariant(
            long cartId, long merchantId, String merchantProductId, String merchantVariantId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_ITEM_BY_PRODUCT_VARIANT_SQL, ITEM_ROW_MAPPER,
                    cartId, merchantId, merchantProductId, merchantVariantId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CartItem> findItemByPublicId(UUID publicId, long cartId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_ITEM_BY_PUBLIC_ID_SQL, ITEM_ROW_MAPPER, publicId, cartId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void insertItem(UUID publicId, CartItem item, Instant now) {
        jdbcTemplate.update(
                INSERT_ITEM_SQL,
                publicId,
                item.cartId(),
                item.merchantId(),
                item.merchantProductId(),
                item.merchantVariantId(),
                item.productName(),
                item.productUrl(),
                item.imageUrl(),
                item.selectedOptions(),
                item.quantity(),
                item.unitPriceAmount(),
                item.currency(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public void updateItemOnMerge(long itemId, CartItem item, Instant now) {
        jdbcTemplate.update(
                UPDATE_ITEM_ON_MERGE_SQL,
                item.quantity(),
                item.productName(),
                item.productUrl(),
                item.imageUrl(),
                item.selectedOptions(),
                item.unitPriceAmount(),
                item.currency(),
                Timestamp.from(now),
                Timestamp.from(now),
                itemId);
    }

    @Override
    public void updateItemVariant(long itemId, CartItem item, Instant now) {
        jdbcTemplate.update(
                UPDATE_ITEM_VARIANT_SQL,
                item.merchantVariantId(),
                item.quantity(),
                item.productName(),
                item.productUrl(),
                item.imageUrl(),
                item.selectedOptions(),
                item.unitPriceAmount(),
                item.currency(),
                Timestamp.from(now),
                Timestamp.from(now),
                itemId);
    }

    @Override
    public int deleteItemById(long itemId) {
        return jdbcTemplate.update(DELETE_ITEM_BY_ID_SQL, itemId);
    }

    @Override
    public int updateItemQuantity(UUID publicId, long cartId, int quantity, Instant now) {
        return jdbcTemplate.update(
                UPDATE_ITEM_QUANTITY_SQL, quantity, Timestamp.from(now), publicId, cartId);
    }

    @Override
    public int deleteItem(UUID publicId, long cartId) {
        return jdbcTemplate.update(DELETE_ITEM_SQL, publicId, cartId);
    }

    @Override
    public List<CartItem> findItemsByCartId(long cartId) {
        return jdbcTemplate.query(FIND_ITEMS_BY_CART_ID_SQL, ITEM_ROW_MAPPER, cartId);
    }

    private static Cart mapCart(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Cart(
                resultSet.getLong("id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getLong("user_id"),
                resultSet.getInt("version"));
    }

    private static CartItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CartItem(
                resultSet.getLong("id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getLong("cart_id"),
                resultSet.getLong("merchant_id"),
                resultSet.getString("merchant_product_id"),
                resultSet.getString("merchant_variant_id"),
                resultSet.getString("product_name"),
                resultSet.getString("product_url"),
                resultSet.getString("image_url"),
                resultSet.getString("selected_options"),
                resultSet.getInt("quantity"),
                resultSet.getLong("unit_price_amount"),
                resultSet.getString("currency"));
    }
}
