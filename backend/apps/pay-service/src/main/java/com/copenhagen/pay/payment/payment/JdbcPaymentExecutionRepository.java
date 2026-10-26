package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.policy.PaymentStatus;
import com.copenhagen.pay.payment.reconciliation.ReconciliationPaymentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentExecutionRepository implements PaymentExecutionRepository, ReconciliationPaymentRepository {

    private static final String FIND_PAYMENT_SQL = """
            SELECT p.id, c.authorization_session_id, p.merchant_account_id,
                   c.merchant_id, p.external_checkout_id, p.purchase_request_id,
                   p.credential_token_id, c.status AS credential_status,
                   payment_mandate.payment_method_ref, p.amount_minor, p.currency,
                   p.status, p.idempotency_key, p.request_hash,
                   attempt.id AS attempt_id, attempt.pg_request_id,
                   attempt.status AS attempt_status,
                   pg_transaction.pg_transaction_id,
                   pg_transaction.status AS pg_transaction_status,
                   pg_transaction.response_code AS pg_response_code,
                   receipt.id AS receipt_id
            FROM payments p
            JOIN payment_credential_tokens c
              ON c.id = p.credential_token_id
            JOIN payment_mandates payment_mandate
              ON payment_mandate.id = c.payment_mandate_id
            LEFT JOIN payment_attempts attempt
              ON attempt.payment_id = p.id AND attempt.operation = 'CHARGE'
            LEFT JOIN pg_transactions pg_transaction
              ON pg_transaction.payment_attempt_id = attempt.id
            LEFT JOIN payment_receipts receipt
              ON receipt.payment_id = p.id
            WHERE p.id = ?
            """;

    private static final String FIND_PAYMENT_FOR_UPDATE_SQL = FIND_PAYMENT_SQL + " FOR UPDATE OF p";

    private static final String FIND_BY_ATTEMPT_SQL = FIND_PAYMENT_SQL
            .replace("WHERE p.id = ?", "WHERE attempt.id = ?");

    private static final String RESOLVE_UNKNOWN_ATTEMPT_SQL = """
            UPDATE payment_attempts SET status = ?, error_code = ?, completed_at = ?
            WHERE id = ? AND operation = 'CHARGE' AND status = 'UNKNOWN'
            """;

    private static final String UPDATE_UNKNOWN_TRANSACTION_SQL = """
            UPDATE pg_transactions
            SET status = ?, pg_transaction_id = COALESCE(?, pg_transaction_id), response_code = ?,
                last_checked_at = ?, completed_at = CASE WHEN ? = 'UNKNOWN' THEN completed_at ELSE ? END
            WHERE payment_attempt_id = ? AND status = 'UNKNOWN'
            """;

    private static final String INSERT_ATTEMPT_SQL = """
            INSERT INTO payment_attempts
                (id, payment_id, attempt_no, operation, request_hash,
                 pg_request_id, status, started_at)
            VALUES (?, ?, 1, 'CHARGE', ?, ?, 'PROCESSING', ?)
            """;

    private static final String INSERT_PG_TRANSACTION_SQL = """
            INSERT INTO pg_transactions
                (id, payment_attempt_id, status, requested_at)
            VALUES (?, ?, 'PROCESSING', ?)
            """;

    private static final String UPDATE_ATTEMPT_SQL = """
            UPDATE payment_attempts
            SET status = ?, error_code = ?, completed_at = ?
            WHERE id = ? AND status = 'PROCESSING'
            """;

    private static final String UPDATE_PG_TRANSACTION_SQL = """
            UPDATE pg_transactions
            SET status = ?,
                pg_transaction_id = COALESCE(?, pg_transaction_id),
                response_code = ?,
                completed_at = ?,
                last_checked_at = ?
            WHERE payment_attempt_id = ? AND status = 'PROCESSING'
            """;

    private static final String INSERT_RECEIPT_SQL = """
            INSERT INTO payment_receipts
                (id, payment_id, receipt_hash, status, artifact_encrypted, issued_at)
            VALUES (?, ?, ?, 'ISSUED', ?, ?)
            ON CONFLICT (payment_id) DO NOTHING
            """;

    private static final String INSERT_RECONCILIATION_JOB_SQL = """
            INSERT INTO payment_reconciliation_jobs
                (id, payment_attempt_id, reason, request_id, status, next_run_at, attempt_count)
            VALUES (?, ?, ?, ?, 'PENDING', ?, 0)
            ON CONFLICT (payment_attempt_id, reason) DO NOTHING
            """;

    private static final RowMapper<PaymentExecutionRecord> ROW_MAPPER =
            JdbcPaymentExecutionRepository::mapPayment;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentExecutionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentExecutionRecord> findByAttemptId(String attemptId) {
        return jdbcTemplate.query(FIND_BY_ATTEMPT_SQL, ROW_MAPPER, attemptId).stream().findFirst();
    }

    @Override
    public Optional<PaymentExecutionRecord> findByAttemptIdForUpdate(String attemptId) {
        return jdbcTemplate.query(FIND_BY_ATTEMPT_SQL + " FOR UPDATE OF p", ROW_MAPPER, attemptId)
                .stream().findFirst();
    }

    @Override
    public boolean resolveUnknownAttempt(String attemptId, String status, String errorCode, Instant now) {
        return jdbcTemplate.update(RESOLVE_UNKNOWN_ATTEMPT_SQL,
                status, errorCode, Timestamp.from(now), attemptId) == 1;
    }

    @Override
    public boolean updateUnknownTransaction(
            String attemptId, String status, String pgTransactionId, String responseCode, Instant now) {
        return jdbcTemplate.update(UPDATE_UNKNOWN_TRANSACTION_SQL,
                status, pgTransactionId, responseCode, Timestamp.from(now),
                status, Timestamp.from(now), attemptId) == 1;
    }

    @Override
    public Optional<PaymentExecutionRecord> findByIdForUpdate(String paymentId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_PAYMENT_FOR_UPDATE_SQL,
                    ROW_MAPPER,
                    paymentId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<PaymentExecutionRecord> findById(String paymentId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_PAYMENT_SQL,
                    ROW_MAPPER,
                    paymentId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void insertChargeAttempt(
            String attemptId,
            String paymentId,
            String requestHash,
            String pgRequestId,
            Instant startedAt) {
        jdbcTemplate.update(
                INSERT_ATTEMPT_SQL,
                attemptId,
                paymentId,
                requestHash,
                pgRequestId,
                Timestamp.from(startedAt));
    }

    @Override
    public void insertPgTransaction(
            String transactionId,
            String attemptId,
            Instant requestedAt) {
        jdbcTemplate.update(
                INSERT_PG_TRANSACTION_SQL,
                transactionId,
                attemptId,
                Timestamp.from(requestedAt));
    }

    @Override
    public boolean updateAttemptResult(
            String attemptId,
            String status,
            String errorCode,
            Instant completedAt) {
        return jdbcTemplate.update(
                UPDATE_ATTEMPT_SQL,
                status,
                errorCode,
                Timestamp.from(completedAt),
                attemptId) == 1;
    }

    @Override
    public boolean updatePgTransactionResult(
            String attemptId,
            String status,
            String pgTransactionId,
            String responseCode,
            Instant completedAt,
            Instant lastCheckedAt) {
        return jdbcTemplate.update(
                UPDATE_PG_TRANSACTION_SQL,
                status,
                pgTransactionId,
                responseCode,
                Timestamp.from(completedAt),
                Timestamp.from(lastCheckedAt),
                attemptId) == 1;
    }

    @Override
    public void insertReceipt(PaymentReceipt receipt) {
        jdbcTemplate.update(
                INSERT_RECEIPT_SQL,
                receipt.id(),
                receipt.paymentId(),
                receipt.receiptHash(),
                receipt.artifactEncrypted(),
                Timestamp.from(receipt.issuedAt()));
    }

    @Override
    public void insertReconciliationJob(
            String attemptId,
            String reason,
            Instant nextRunAt) {
        insertReconciliationJob(attemptId, reason, nextRunAt, null);
    }

    @Override
    public void insertReconciliationJob(
            String attemptId,
            String reason,
            Instant nextRunAt,
            String requestId) {
        jdbcTemplate.update(
                INSERT_RECONCILIATION_JOB_SQL,
                java.util.UUID.randomUUID().toString(),
                attemptId,
                reason,
                requestId,
                Timestamp.from(nextRunAt));
    }

    private static PaymentExecutionRecord mapPayment(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        String status = resultSet.getString("status");
        return new PaymentExecutionRecord(
                resultSet.getString("id"),
                resultSet.getString("authorization_session_id"),
                resultSet.getString("merchant_account_id"),
                resultSet.getString("merchant_id"),
                resultSet.getString("external_checkout_id"),
                resultSet.getString("purchase_request_id"),
                resultSet.getString("credential_token_id"),
                resultSet.getString("credential_status"),
                resultSet.getString("payment_method_ref"),
                resultSet.getLong("amount_minor"),
                resultSet.getString("currency"),
                PaymentStatus.valueOf(status),
                resultSet.getString("idempotency_key"),
                resultSet.getString("request_hash"),
                resultSet.getString("attempt_id"),
                resultSet.getString("pg_request_id"),
                resultSet.getString("attempt_status"),
                resultSet.getString("pg_transaction_id"),
                resultSet.getString("pg_transaction_status"),
                resultSet.getString("pg_response_code"),
                resultSet.getString("receipt_id"));
    }
}
