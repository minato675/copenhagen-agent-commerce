package com.copenhagen.core.agent.refreshtoken;

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
public class JdbcAgentRefreshTokenRepository implements AgentRefreshTokenRepository {

    private static final String INSERT_SQL = """
            INSERT INTO agent_refresh_tokens
                (connection_id, token_hash, scopes, issued_at, expires_at, created_at)
            VALUES (?, ?, ?::jsonb, ?, ?, ?)
            RETURNING id
            """;

    private static final String SELECT_COLUMNS = """
            SELECT id, connection_id, token_hash, scopes::text AS scopes, issued_at, expires_at,
                revoked_at, last_used_at, created_at
            FROM agent_refresh_tokens
            WHERE token_hash = ?
            """;

    private static final String FIND_BY_TOKEN_HASH_FOR_UPDATE_SQL = SELECT_COLUMNS + "FOR UPDATE\n";

    private static final String REVOKE_BY_ID_SQL = """
            UPDATE agent_refresh_tokens
            SET revoked_at = ?, last_used_at = ?
            WHERE id = ? AND revoked_at IS NULL
            """;

    private static final String REVOKE_ALL_ACTIVE_BY_CONNECTION_ID_SQL = """
            UPDATE agent_refresh_tokens
            SET revoked_at = ?
            WHERE connection_id = ? AND revoked_at IS NULL
            """;

    private static final RowMapper<AgentRefreshToken> ROW_MAPPER = JdbcAgentRefreshTokenRepository::mapToken;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAgentRefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(long connectionId, String tokenHash, String scopesJson, Instant expiresAt, Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                connectionId,
                tokenHash,
                scopesJson,
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                Timestamp.from(now));
    }

    @Override
    public Optional<AgentRefreshToken> findByTokenHash(String tokenHash) {
        return queryOne(SELECT_COLUMNS, tokenHash);
    }

    @Override
    public Optional<AgentRefreshToken> findByTokenHashForUpdate(String tokenHash) {
        return queryOne(FIND_BY_TOKEN_HASH_FOR_UPDATE_SQL, tokenHash);
    }

    @Override
    public void revokeById(long id, Instant now) {
        jdbcTemplate.update(REVOKE_BY_ID_SQL, Timestamp.from(now), Timestamp.from(now), id);
    }

    @Override
    public int revokeAllActiveByConnectionId(long connectionId, Instant now) {
        return jdbcTemplate.update(REVOKE_ALL_ACTIVE_BY_CONNECTION_ID_SQL, Timestamp.from(now), connectionId);
    }

    private Optional<AgentRefreshToken> queryOne(String sql, String tokenHash) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, tokenHash));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static AgentRefreshToken mapToken(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        Timestamp lastUsedAt = resultSet.getTimestamp("last_used_at");
        return new AgentRefreshToken(
                resultSet.getLong("id"),
                resultSet.getLong("connection_id"),
                resultSet.getString("token_hash"),
                resultSet.getString("scopes"),
                resultSet.getTimestamp("issued_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                lastUsedAt == null ? null : lastUsedAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
