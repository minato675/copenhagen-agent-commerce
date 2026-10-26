package com.copenhagen.pay.payment.credential.biometric;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBiometricCredentialRepository implements BiometricCredentialRepository {

    private static final String FIND_ACTIVE_SQL = """
            SELECT id, user_id, device_id, public_key, status, registered_at, revoked_at
            FROM biometric_credentials
            WHERE user_id = ? AND device_id = ? AND status = 'ACTIVE'
            """;

    private static final String INSERT_SQL = """
            INSERT INTO biometric_credentials
                (id, user_id, device_id, public_key, status, registered_at, revoked_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)
            """;

    private static final String FIND_ACTIVE_BY_USER_SQL = """
            SELECT id, user_id, device_id, public_key, status, registered_at, revoked_at
            FROM biometric_credentials
            WHERE user_id = ? AND status = 'ACTIVE'
            ORDER BY registered_at, id
            """;

    private static final String FIND_ACTIVE_BY_ID_AND_USER_SQL = """
            SELECT id, user_id, device_id, public_key, status, registered_at, revoked_at
            FROM biometric_credentials
            WHERE id = ? AND user_id = ? AND status = 'ACTIVE'
            """;

    private static final String REVOKE_SQL = """
            UPDATE biometric_credentials
            SET status = 'REVOKED', revoked_at = ?
            WHERE user_id = ? AND device_id = ? AND status = 'ACTIVE'
            """;

    private static final RowMapper<BiometricCredential> ROW_MAPPER =
            JdbcBiometricCredentialRepository::mapCredential;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBiometricCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<BiometricCredential> findActiveByUserAndDevice(
            String userId,
            String deviceId) {
        return jdbcTemplate.query(
                        FIND_ACTIVE_SQL,
                        ROW_MAPPER,
                        userId,
                        deviceId)
                .stream()
                .findFirst();
    }

    @Override
    public List<BiometricCredential> findActiveByUser(String userId) {
        return jdbcTemplate.query(FIND_ACTIVE_BY_USER_SQL, ROW_MAPPER, userId);
    }

    @Override
    public Optional<BiometricCredential> findActiveByIdAndUser(
            String credentialId,
            String userId) {
        return jdbcTemplate.query(
                        FIND_ACTIVE_BY_ID_AND_USER_SQL,
                        ROW_MAPPER,
                        credentialId,
                        userId)
                .stream()
                .findFirst();
    }

    @Override
    public void insert(
            String id,
            String userId,
            String deviceId,
            String publicKey,
            Instant registeredAt) {
        jdbcTemplate.update(
                INSERT_SQL,
                id,
                userId,
                deviceId,
                publicKey,
                Timestamp.from(registeredAt));
    }

    @Override
    public boolean revoke(String userId, String deviceId, Instant revokedAt) {
        return jdbcTemplate.update(
                REVOKE_SQL,
                Timestamp.from(revokedAt),
                userId,
                deviceId) > 0;
    }

    private static BiometricCredential mapCredential(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        Timestamp registeredAt = resultSet.getTimestamp("registered_at");
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        return new BiometricCredential(
                resultSet.getString("id"),
                resultSet.getString("user_id"),
                resultSet.getString("device_id"),
                resultSet.getString("public_key"),
                BiometricCredentialStatus.valueOf(resultSet.getString("status")),
                registeredAt.toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }
}
