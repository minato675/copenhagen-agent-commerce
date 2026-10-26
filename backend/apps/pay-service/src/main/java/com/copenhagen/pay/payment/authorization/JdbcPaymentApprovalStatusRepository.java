package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentApprovalStatusRepository implements PaymentApprovalStatusRepository {

    /** 판매처 수만큼 행이 나온다. credential·payment 는 아직 없을 수 있어 LEFT JOIN 이다. */
    private static final String FIND_STATUS_SQL = """
            SELECT s.id AS approval_id,
                   s.status AS approval_status,
                   s.total_amount_minor,
                   s.currency,
                   m.merchant_id AS item_merchant_id,
                   i.external_checkout_id AS item_checkout_id,
                   p.id AS payment_id,
                   p.status AS payment_status,
                   c.id AS credential_id,
                   c.expires_at AS credential_expires_at,
                   CASE
                       WHEN pm.last4 IS NULL OR BTRIM(pm.last4) = '' THEN 'Copenhagen Pay'
                       ELSE COALESCE(NULLIF(BTRIM(pm.brand), ''), 'CARD') || ' '
                            || CHR(8226) || CHR(8226) || CHR(8226) || CHR(8226) || ' ' || BTRIM(pm.last4)
                   END AS payment_label
            FROM authorization_sessions s
            JOIN authorization_session_items i
              ON i.authorization_session_id = s.id
            JOIN merchant_accounts m
              ON m.id = i.merchant_account_id
            LEFT JOIN payment_credential_tokens c
              ON c.session_item_id = i.id
            LEFT JOIN payments p
              ON p.credential_token_id = c.id
            LEFT JOIN payment_methods pm
              ON pm.id = s.selected_payment_method_id
             AND pm.user_id = s.user_id
            WHERE s.id = ?
            ORDER BY i.created_at, i.id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentApprovalStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentApprovalStatusView> findByApprovalId(String approvalId) {
        return Optional.ofNullable(jdbcTemplate.query(
                FIND_STATUS_SQL,
                new StatusExtractor(),
                approvalId));
    }

    private static final class StatusExtractor
            implements ResultSetExtractor<PaymentApprovalStatusView> {

        @Override
        public PaymentApprovalStatusView extractData(ResultSet resultSet) throws SQLException {
            String approvalId = null;
            AuthorizationSessionStatus approvalStatus = null;
            long totalAmountMinor = 0;
            String currency = null;
            List<ItemView> items = new ArrayList<>();
            while (resultSet.next()) {
                if (approvalId == null) {
                    approvalId = resultSet.getString("approval_id");
                    approvalStatus = AuthorizationSessionStatus.valueOf(
                            resultSet.getString("approval_status"));
                    totalAmountMinor = resultSet.getLong("total_amount_minor");
                    currency = resultSet.getString("currency");
                }
                String paymentStatus = resultSet.getString("payment_status");
                Timestamp credentialExpiresAt = resultSet.getTimestamp("credential_expires_at");
                items.add(new ItemView(
                        resultSet.getString("item_merchant_id"),
                        resultSet.getString("item_checkout_id"),
                        resultSet.getString("credential_id"),
                        credentialExpiresAt == null ? null : credentialExpiresAt.toInstant(),
                        resultSet.getString("payment_id"),
                        paymentStatus == null ? null : PaymentStatus.valueOf(paymentStatus),
                        resultSet.getString("payment_label")));
            }
            if (approvalId == null) {
                return null;
            }
            return new PaymentApprovalStatusView(
                    approvalId,
                    approvalStatus,
                    totalAmountMinor,
                    currency,
                    items);
        }
    }
}
