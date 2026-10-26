package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentCredentialConsumptionRepository
        implements PaymentCredentialConsumptionRepository {

    private static final String FIND_PAYMENT_BY_IDEMPOTENCY_SQL = """
            SELECT id, merchant_account_id, external_checkout_id, purchase_request_id,
                   credential_token_id, amount_minor, currency, status,
                   idempotency_key, request_hash
            FROM payments
            WHERE merchant_account_id = ? AND idempotency_key = ?
            """;

    private static final String FIND_CREDENTIAL_FOR_UPDATE_SQL = """
            SELECT c.id, c.authorization_session_id, i.merchant_account_id,
                   c.merchant_id, s.purchase_request_id, c.external_checkout_id,
                   c.checkout_digest, c.amount_minor, c.currency, c.status,
                   c.expires_at, c.consumed_at,
                   checkout_mandate.status AS checkout_mandate_status,
                   payment_mandate.status AS payment_mandate_status
            FROM payment_credential_tokens c
            JOIN authorization_sessions s ON s.id = c.authorization_session_id
            JOIN authorization_session_items i ON i.id = c.session_item_id
            JOIN checkout_mandates checkout_mandate
              ON checkout_mandate.id = c.checkout_mandate_id
            JOIN payment_mandates payment_mandate
              ON payment_mandate.id = c.payment_mandate_id
            WHERE c.id = ?
            FOR UPDATE OF c
            """;

    private static final String INSERT_PAYMENT_SQL = """
            INSERT INTO payments
                (id, merchant_account_id, external_checkout_id, purchase_request_id,
                 credential_token_id, amount_minor, currency, status,
                 idempotency_key, request_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String CONSUME_CREDENTIAL_SQL = """
            UPDATE payment_credential_tokens
            SET status = 'CONSUMED', consumed_at = ?
            WHERE id = ? AND status = 'ISSUED'
            """;

    private static final RowMapper<CreatedPayment> PAYMENT_ROW_MAPPER =
            JdbcPaymentCredentialConsumptionRepository::mapCreatedPayment;
    private static final RowMapper<PaymentCredentialRecord> CREDENTIAL_ROW_MAPPER =
            JdbcPaymentCredentialConsumptionRepository::mapCredential;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentCredentialConsumptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CreatedPayment> findPaymentByMerchantAndIdempotencyKey(
            String merchantAccountId,
            String idempotencyKey) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_PAYMENT_BY_IDEMPOTENCY_SQL,
                    PAYMENT_ROW_MAPPER,
                    merchantAccountId,
                    idempotencyKey));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<PaymentCredentialRecord> findCredentialForUpdate(String credentialId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_CREDENTIAL_FOR_UPDATE_SQL,
                    CREDENTIAL_ROW_MAPPER,
                    credentialId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void insertPayment(CreatedPayment payment) {
        jdbcTemplate.update(
                INSERT_PAYMENT_SQL,
                payment.id(),
                payment.merchantAccountId(),
                payment.externalCheckoutId(),
                payment.purchaseRequestId(),
                payment.credentialTokenId(),
                payment.amountMinor(),
                payment.currency(),
                payment.status().name(),
                payment.idempotencyKey(),
                payment.requestHash());
    }

    @Override
    public boolean consumeCredential(String credentialId, Instant consumedAt) {
        return jdbcTemplate.update(
                CONSUME_CREDENTIAL_SQL,
                Timestamp.from(consumedAt),
                credentialId) == 1;
    }

    private static CreatedPayment mapCreatedPayment(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new CreatedPayment(
                resultSet.getString("id"),
                resultSet.getString("merchant_account_id"),
                resultSet.getString("external_checkout_id"),
                resultSet.getString("purchase_request_id"),
                resultSet.getString("credential_token_id"),
                resultSet.getLong("amount_minor"),
                resultSet.getString("currency"),
                PaymentStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("idempotency_key"),
                resultSet.getString("request_hash"));
    }

    private static PaymentCredentialRecord mapCredential(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new PaymentCredentialRecord(
                resultSet.getString("id"),
                resultSet.getString("authorization_session_id"),
                resultSet.getString("merchant_account_id"),
                resultSet.getString("merchant_id"),
                resultSet.getString("purchase_request_id"),
                resultSet.getString("external_checkout_id"),
                resultSet.getString("checkout_digest"),
                resultSet.getLong("amount_minor"),
                resultSet.getString("currency"),
                PaymentCredentialStatus.valueOf(resultSet.getString("status")),
                toInstant(resultSet, "expires_at"),
                toInstant(resultSet, "consumed_at"),
                MandateStatus.valueOf(resultSet.getString("checkout_mandate_status")),
                MandateStatus.valueOf(resultSet.getString("payment_mandate_status")));
    }

    private static Instant toInstant(ResultSet resultSet, String columnName) throws SQLException {
        OffsetDateTime value = resultSet.getObject(columnName, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
