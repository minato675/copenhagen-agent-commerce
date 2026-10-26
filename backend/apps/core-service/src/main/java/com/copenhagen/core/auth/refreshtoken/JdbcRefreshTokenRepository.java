package com.copenhagen.core.auth.refreshtoken;

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
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private static final String INSERT_SQL = """
            INSERT INTO refresh_tokens
                (user_id, token_hash, device_id, device_name, issued_at, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String SELECT_COLUMNS = """
            SELECT id, user_id, token_hash, device_id, device_name, issued_at, expires_at,
                revoked_at, last_used_at, created_at
            FROM refresh_tokens
            WHERE token_hash = ?
            """;

    private static final String FIND_BY_TOKEN_HASH_SQL = SELECT_COLUMNS;

    private static final String FIND_BY_TOKEN_HASH_FOR_UPDATE_SQL = SELECT_COLUMNS + "FOR UPDATE\n";

    private static final String REVOKE_BY_ID_SQL = """
            UPDATE refresh_tokens
            SET revoked_at = ?, last_used_at = ?
            WHERE id = ? AND revoked_at IS NULL
            """;

    private static final String REVOKE_ACTIVE_BY_USER_ID_AND_DEVICE_ID_SQL = """
            UPDATE refresh_tokens
            SET revoked_at = ?
            WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL
            """;

    private static final String REVOKE_ALL_ACTIVE_BY_USER_ID_SQL = """
            UPDATE refresh_tokens
            SET revoked_at = ?
            WHERE user_id = ? AND revoked_at IS NULL
            """;

    private static final RowMapper<RefreshToken> ROW_MAPPER = JdbcRefreshTokenRepository::mapRefreshToken;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(
            long userId,
            String tokenHash,
            String deviceId,
            String deviceName,
            Instant expiresAt,
            Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                userId,
                tokenHash,
                deviceId,
                deviceName,
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                Timestamp.from(now));
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return queryOne(FIND_BY_TOKEN_HASH_SQL, tokenHash);
    }

    @Override
    public Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash) {
        return queryOne(FIND_BY_TOKEN_HASH_FOR_UPDATE_SQL, tokenHash);
    }

    @Override
    public void revokeById(long id, Instant now) {
        jdbcTemplate.update(REVOKE_BY_ID_SQL, Timestamp.from(now), Timestamp.from(now), id);
    }

    @Override
    public void revokeActiveByUserIdAndDeviceId(long userId, String deviceId, Instant now) {
        jdbcTemplate.update(REVOKE_ACTIVE_BY_USER_ID_AND_DEVICE_ID_SQL, Timestamp.from(now), userId, deviceId);
    }

    @Override
    public int revokeAllActiveByUserId(long userId, Instant now) {
        return jdbcTemplate.update(REVOKE_ALL_ACTIVE_BY_USER_ID_SQL, Timestamp.from(now), userId);
    }

    private Optional<RefreshToken> queryOne(String sql, String tokenHash) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, tokenHash));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static RefreshToken mapRefreshToken(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        Timestamp lastUsedAt = resultSet.getTimestamp("last_used_at");
        return new RefreshToken(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("token_hash"),
                resultSet.getString("device_id"),
                resultSet.getString("device_name"),
                resultSet.getTimestamp("issued_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                lastUsedAt == null ? null : lastUsedAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
