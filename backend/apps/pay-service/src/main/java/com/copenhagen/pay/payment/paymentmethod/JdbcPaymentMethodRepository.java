package com.copenhagen.pay.payment.paymentmethod;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentMethodRepository implements PaymentMethodRepository {

    private static final String FIND_ACTIVE_BY_USER_SQL = """
            SELECT id, user_id, provider, provider_token_ref, brand, last4,
                   expiry_month, expiry_year, status, is_default
            FROM payment_methods
            WHERE user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
            ORDER BY is_default DESC, id
            """;

    private static final String FIND_ACTIVE_BY_ID_FOR_UPDATE_SQL = """
            SELECT id, user_id, provider, provider_token_ref, brand, last4,
                   expiry_month, expiry_year, status, is_default
            FROM payment_methods
            WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
            FOR UPDATE
            """;

    private static final String INSERT_SQL = """
            INSERT INTO payment_methods
                (id, user_id, provider, provider_token_ref, brand, last4,
                 expiry_month, expiry_year, status, is_default, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, NULL)
            """;

    private static final String CLEAR_DEFAULT_SQL = """
            UPDATE payment_methods
            SET is_default = FALSE
            WHERE user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
            """;

    private static final String MARK_DEFAULT_SQL = """
            UPDATE payment_methods
            SET is_default = TRUE
            WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
            """;

    private static final String SOFT_DELETE_SQL = """
            UPDATE payment_methods
            SET status = 'REVOKED', is_default = FALSE, deleted_at = CURRENT_TIMESTAMP
            WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
            """;

    private static final RowMapper<PaymentMethod> ROW_MAPPER =
            JdbcPaymentMethodRepository::mapPaymentMethod;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentMethodRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<PaymentMethod> findActiveByUserId(String userId) {
        return jdbcTemplate.query(
                FIND_ACTIVE_BY_USER_SQL,
                ROW_MAPPER,
                userId);
    }

    @Override
    public Optional<PaymentMethod> findActiveByIdAndUserIdForUpdate(
            String paymentMethodId,
            String userId) {
        return jdbcTemplate.query(
                        FIND_ACTIVE_BY_ID_FOR_UPDATE_SQL,
                        ROW_MAPPER,
                        paymentMethodId,
                        userId)
                .stream()
                .findFirst();
    }

    @Override
    public void insert(PaymentMethod paymentMethod) {
        jdbcTemplate.update(
                INSERT_SQL,
                paymentMethod.id(),
                paymentMethod.userId(),
                paymentMethod.provider(),
                paymentMethod.providerTokenRef(),
                paymentMethod.brand(),
                paymentMethod.last4(),
                paymentMethod.expiryMonth(),
                paymentMethod.expiryYear(),
                paymentMethod.defaultMethod());
    }

    @Override
    public int clearDefaultForUser(String userId) {
        return jdbcTemplate.update(CLEAR_DEFAULT_SQL, userId);
    }

    @Override
    public int markDefault(String paymentMethodId, String userId) {
        return jdbcTemplate.update(MARK_DEFAULT_SQL, paymentMethodId, userId);
    }

    @Override
    public int softDelete(String paymentMethodId, String userId) {
        return jdbcTemplate.update(SOFT_DELETE_SQL, paymentMethodId, userId);
    }

    private static PaymentMethod mapPaymentMethod(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        String last4 = resultSet.getString("last4");
        return new PaymentMethod(
                resultSet.getString("id"),
                resultSet.getString("user_id"),
                resultSet.getString("provider"),
                resultSet.getString("provider_token_ref"),
                resultSet.getString("brand"),
                last4 == null ? null : last4.trim(),
                getInteger(resultSet, "expiry_month"),
                getInteger(resultSet, "expiry_year"),
                PaymentMethodStatus.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("is_default"));
    }

    private static Integer getInteger(
            ResultSet resultSet,
            String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
