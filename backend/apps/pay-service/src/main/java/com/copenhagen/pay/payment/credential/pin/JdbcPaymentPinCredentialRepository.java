package com.copenhagen.pay.payment.credential.pin;

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
public class JdbcPaymentPinCredentialRepository implements PaymentPinCredentialRepository {

    private static final String FIND_BY_USER_FOR_UPDATE_SQL = """
            SELECT id, user_id, pin_hash, failed_attempts, locked_until, status,
                   registration_idempotency_key, registration_request_hash
            FROM payment_pin_credentials
            WHERE user_id = ?
            FOR UPDATE
            """;

    private static final String FIND_BY_USER_SQL = """
            SELECT id, user_id, pin_hash, failed_attempts, locked_until, status,
                   registration_idempotency_key, registration_request_hash
            FROM payment_pin_credentials
            WHERE user_id = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO payment_pin_credentials
                (id, user_id, pin_hash, failed_attempts, locked_until, status,
                 registration_idempotency_key, registration_request_hash, updated_at)
            VALUES (?, ?, ?, 0, NULL, 'ACTIVE', ?, ?, ?)
            """;

    private static final String UPDATE_PIN_SQL = """
            UPDATE payment_pin_credentials
            SET pin_hash = ?, failed_attempts = 0, locked_until = NULL,
                status = 'ACTIVE', updated_at = ?
            WHERE id = ?
            """;

    private static final String UPDATE_FAILURE_SQL = """
            UPDATE payment_pin_credentials
            SET failed_attempts = ?, locked_until = ?, status = ?, updated_at = ?
            WHERE id = ?
            """;

    private static final String RESET_VERIFICATION_STATE_SQL = """
            UPDATE payment_pin_credentials
            SET failed_attempts = 0, locked_until = NULL, status = 'ACTIVE', updated_at = ?
            WHERE id = ?
            """;

    private static final RowMapper<PaymentPinCredential> ROW_MAPPER =
            JdbcPaymentPinCredentialRepository::mapCredential;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentPinCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentPinCredential> findByUserId(String userId) {
        return find(FIND_BY_USER_SQL, userId);
    }

    @Override
    public Optional<PaymentPinCredential> findByUserIdForUpdate(String userId) {
        return find(FIND_BY_USER_FOR_UPDATE_SQL, userId);
    }

    private Optional<PaymentPinCredential> find(String sql, String userId) {
        try {
            PaymentPinCredential credential = jdbcTemplate.queryForObject(
                    sql,
                    ROW_MAPPER,
                    userId);
            return Optional.ofNullable(credential);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void insert(
            String id,
            String userId,
            String pinHash,
            Instant updatedAt) {
        insert(id, userId, pinHash, null, null, updatedAt);
    }

    @Override
    public void insert(
            String id,
            String userId,
            String pinHash,
            String registrationIdempotencyKey,
            String registrationRequestHash,
            Instant updatedAt) {
        jdbcTemplate.update(
                INSERT_SQL,
                id,
                userId,
                pinHash,
                registrationIdempotencyKey,
                registrationRequestHash,
                Timestamp.from(updatedAt));
    }

    @Override
    public void updatePin(
            String id,
            String pinHash,
            Instant updatedAt) {
        jdbcTemplate.update(
                UPDATE_PIN_SQL,
                pinHash,
                Timestamp.from(updatedAt),
                id);
    }

    @Override
    public void updateVerificationFailure(
            String id,
            int failedAttempts,
            Instant lockedUntil,
            PaymentPinStatus status,
            Instant updatedAt) {
        jdbcTemplate.update(
                UPDATE_FAILURE_SQL,
                failedAttempts,
                lockedUntil == null ? null : Timestamp.from(lockedUntil),
                status.name(),
                Timestamp.from(updatedAt),
                id);
    }

    @Override
    public void resetVerificationState(String id, Instant updatedAt) {
        jdbcTemplate.update(
                RESET_VERIFICATION_STATE_SQL,
                Timestamp.from(updatedAt),
                id);
    }

    private static PaymentPinCredential mapCredential(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        Timestamp lockedUntil = resultSet.getTimestamp("locked_until");
        return new PaymentPinCredential(
                resultSet.getString("id"),
                resultSet.getString("user_id"),
                resultSet.getString("pin_hash"),
                resultSet.getInt("failed_attempts"),
                lockedUntil == null ? null : lockedUntil.toInstant(),
                PaymentPinStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("registration_idempotency_key"),
                resultSet.getString("registration_request_hash"));
    }
}
