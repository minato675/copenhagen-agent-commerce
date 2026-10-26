package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMerchantPaymentQueryRepository implements MerchantPaymentQueryRepository {

    private static final String FIND_PAYMENT_SQL = """
            SELECT payment.id, payment.status, payment.amount_minor, payment.currency
            FROM payments payment
            JOIN merchant_accounts merchant
              ON merchant.id = payment.merchant_account_id
            WHERE merchant.merchant_id = ?
              AND merchant.status = 'ACTIVE'
              AND payment.id = ?
            """;

    private static final RowMapper<PaymentView> ROW_MAPPER =
            JdbcMerchantPaymentQueryRepository::mapPayment;

    private final JdbcTemplate jdbcTemplate;

    public JdbcMerchantPaymentQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentView> findByMerchantIdAndPaymentId(
            String merchantId,
            String paymentId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_PAYMENT_SQL,
                    ROW_MAPPER,
                    merchantId,
                    paymentId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static PaymentView mapPayment(
            java.sql.ResultSet resultSet,
            int rowNumber) throws java.sql.SQLException {
        return new PaymentView(
                resultSet.getString("id"),
                PaymentStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("amount_minor"),
                resultSet.getString("currency"));
    }
}
