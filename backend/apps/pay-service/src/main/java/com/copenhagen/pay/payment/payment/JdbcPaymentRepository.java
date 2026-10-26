package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentRepository implements PaymentRepository {

    private static final String FIND_STATUS_SQL = """
            SELECT status
            FROM payments
            WHERE id = ?
            """;

    private static final String FIND_AUTHORIZATION_SESSION_SQL = """
            SELECT credential.authorization_session_id
            FROM payments payment
            JOIN payment_credential_tokens credential
              ON credential.id = payment.credential_token_id
            WHERE payment.id = ?
            """;

    private static final String FIND_SESSION_ITEM_SQL = """
            SELECT credential.session_item_id
            FROM payments payment
            JOIN payment_credential_tokens credential
              ON credential.id = payment.credential_token_id
            WHERE payment.id = ?
            """;

    private static final String EXISTS_LIVE_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM payments
                WHERE merchant_account_id = ?
                  AND purchase_request_id = ?
                  AND status IN ('READY', 'PROCESSING', 'SUCCESS', 'UNKNOWN')
            )
            """;

    private static final String TRANSITION_STATUS_SQL = """
            UPDATE payments
            SET status = ?,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentStatus> findStatus(String paymentId) {
        try {
            String status = jdbcTemplate.queryForObject(FIND_STATUS_SQL, String.class, paymentId);
            return Optional.of(PaymentStatus.valueOf(status));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> findAuthorizationSessionId(String paymentId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_AUTHORIZATION_SESSION_SQL,
                    String.class,
                    paymentId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> findSessionItemId(String paymentId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_SESSION_ITEM_SQL,
                    String.class,
                    paymentId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean existsLiveByPurchaseRequest(String merchantAccountId, String purchaseRequestId) {
        Boolean exists = jdbcTemplate.queryForObject(
                EXISTS_LIVE_SQL,
                Boolean.class,
                merchantAccountId,
                purchaseRequestId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean transitionStatus(
            String paymentId,
            PaymentStatus expectedStatus,
            PaymentStatus targetStatus) {
        int updated = jdbcTemplate.update(
                TRANSITION_STATUS_SQL,
                targetStatus.name(),
                paymentId,
                expectedStatus.name());
        return updated == 1;
    }
}
