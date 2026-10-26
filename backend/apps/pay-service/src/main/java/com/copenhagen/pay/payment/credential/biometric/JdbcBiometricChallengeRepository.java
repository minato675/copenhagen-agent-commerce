package com.copenhagen.pay.payment.credential.biometric;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBiometricChallengeRepository implements BiometricChallengeRepository {

    private static final String EXPIRE_SESSION_SQL = """
            UPDATE biometric_challenges
            SET status = 'EXPIRED', used_at = ?
            WHERE authorization_session_id = ?
              AND status = 'PENDING'
            """;

    private static final String INSERT_SQL = """
            INSERT INTO biometric_challenges
                (id, biometric_credential_id, authorization_session_id,
                 challenge_hash, challenge_value, status, expires_at, used_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)
            """;

    private static final String FIND_SQL = """
            SELECT id, biometric_credential_id, authorization_session_id,
                   challenge_hash, challenge_value, status, expires_at, used_at, created_at
            FROM biometric_challenges
            WHERE id = ? AND authorization_session_id = ?
            """;

    private static final String MARK_SUCCESS_SQL = """
            UPDATE biometric_challenges
            SET status = 'SUCCESS', used_at = ?
            WHERE id = ?
              AND authorization_session_id = ?
              AND status = 'PENDING'
              AND expires_at > ?
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE biometric_challenges
            SET status = 'FAILED', used_at = ?
            WHERE id = ?
              AND authorization_session_id = ?
              AND status = 'PENDING'
              AND expires_at > ?
            """;

    private static final String MARK_EXPIRED_SQL = """
            UPDATE biometric_challenges
            SET status = 'EXPIRED', used_at = ?
            WHERE id = ?
              AND authorization_session_id = ?
              AND status = 'PENDING'
              AND expires_at <= ?
            """;

    private static final RowMapper<BiometricChallenge> ROW_MAPPER =
            JdbcBiometricChallengeRepository::mapChallenge;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBiometricChallengeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int expirePendingForSession(String authorizationSessionId, Instant now) {
        return jdbcTemplate.update(
                EXPIRE_SESSION_SQL,
                Timestamp.from(now),
                authorizationSessionId);
    }

    @Override
    public void insert(BiometricChallenge challenge) {
        jdbcTemplate.update(
                INSERT_SQL,
                challenge.id(),
                challenge.biometricCredentialId(),
                challenge.authorizationSessionId(),
                challenge.challengeHash(),
                challenge.challengeValue(),
                challenge.status().name(),
                Timestamp.from(challenge.expiresAt()),
                Timestamp.from(challenge.createdAt()));
    }

    @Override
    public Optional<BiometricChallenge> findByIdAndSession(
            String challengeId,
            String authorizationSessionId) {
        return jdbcTemplate.query(FIND_SQL, ROW_MAPPER, challengeId, authorizationSessionId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean markSuccessIfPending(
            String challengeId,
            String authorizationSessionId,
            Instant now) {
        return jdbcTemplate.update(
                MARK_SUCCESS_SQL,
                Timestamp.from(now),
                challengeId,
                authorizationSessionId,
                Timestamp.from(now)) == 1;
    }

    @Override
    public boolean markFailedIfPending(
            String challengeId,
            String authorizationSessionId,
            Instant now) {
        return jdbcTemplate.update(
                MARK_FAILED_SQL,
                Timestamp.from(now),
                challengeId,
                authorizationSessionId,
                Timestamp.from(now)) == 1;
    }

    @Override
    public boolean markExpiredIfPending(
            String challengeId,
            String authorizationSessionId,
            Instant now) {
        return jdbcTemplate.update(
                MARK_EXPIRED_SQL,
                Timestamp.from(now),
                challengeId,
                authorizationSessionId,
                Timestamp.from(now)) == 1;
    }

    private static BiometricChallenge mapChallenge(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        Timestamp expiresAt = resultSet.getTimestamp("expires_at");
        Timestamp usedAt = resultSet.getTimestamp("used_at");
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new BiometricChallenge(
                resultSet.getString("id"),
                resultSet.getString("biometric_credential_id"),
                resultSet.getString("authorization_session_id"),
                resultSet.getString("challenge_hash"),
                resultSet.getString("challenge_value"),
                BiometricChallengeStatus.valueOf(resultSet.getString("status")),
                expiresAt.toInstant(),
                usedAt == null ? null : usedAt.toInstant(),
                createdAt.toInstant());
    }
}
