package com.copenhagen.pay.payment.merchant;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMerchantAccountRepository implements MerchantAccountRepository {

    private static final String FIND_ACTIVE_SQL = """
            SELECT id, merchant_id, display_name, status
            FROM merchant_accounts
            WHERE merchant_id = ? AND status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcMerchantAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<MerchantAccount> findActiveByMerchantId(String merchantId) {
        return jdbcTemplate.query(
                        FIND_ACTIVE_SQL,
                        (resultSet, rowNumber) -> new MerchantAccount(
                                resultSet.getString("id"),
                                resultSet.getString("merchant_id"),
                                resultSet.getString("display_name"),
                                resultSet.getString("status")),
                        merchantId)
                .stream()
                .findFirst();
    }
}
