package com.copenhagen.core.agent.connection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentConnectionRepository implements AgentConnectionRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, public_id, user_id, oauth_client_id, granted_scopes::text AS granted_scopes,
                connected_at, last_used_at, revoked_at, created_at, updated_at
            FROM agent_connections
            """;

    private static final String FIND_ACTIVE_BY_USER_AND_CLIENT_SQL = SELECT_COLUMNS + """
            WHERE user_id = ? AND oauth_client_id = ? AND revoked_at IS NULL
            """;

    private static final String FIND_ACTIVE_BY_PUBLIC_ID_AND_USER_ID_SQL = SELECT_COLUMNS + """
            WHERE public_id = ? AND user_id = ? AND revoked_at IS NULL
            """;

    private static final String FIND_BY_ID_SQL = SELECT_COLUMNS + " WHERE id = ?";

    private static final String INSERT_SQL = """
            INSERT INTO agent_connections
                (public_id, user_id, oauth_client_id, granted_scopes, connected_at, created_at, updated_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
            RETURNING id
            """;

    private static final String REVOKE_SQL = """
            UPDATE agent_connections
            SET revoked_at = ?, updated_at = ?
            WHERE id = ? AND revoked_at IS NULL
            """;

    private static final String FIND_ACTIVE_SUMMARIES_BY_USER_ID_SQL = """
            SELECT ac.public_id AS connection_id, oc.client_name, oc.logo_url,
                ac.granted_scopes::text AS granted_scopes, ac.connected_at, ac.last_used_at
            FROM agent_connections ac
            JOIN oauth_clients oc ON oc.id = ac.oauth_client_id
            WHERE ac.user_id = ? AND ac.revoked_at IS NULL
            ORDER BY ac.connected_at DESC
            """;

    private static final RowMapper<AgentConnection> ROW_MAPPER = JdbcAgentConnectionRepository::mapConnection;
    private static final RowMapper<AgentConnectionSummary> SUMMARY_ROW_MAPPER =
            JdbcAgentConnectionRepository::mapSummary;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAgentConnectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AgentConnection> findActiveByUserIdAndOauthClientId(long userId, long oauthClientId) {
        return queryOne(FIND_ACTIVE_BY_USER_AND_CLIENT_SQL, userId, oauthClientId);
    }

    @Override
    public Optional<AgentConnection> findActiveByPublicIdAndUserId(UUID publicId, long userId) {
        return queryOne(FIND_ACTIVE_BY_PUBLIC_ID_AND_USER_ID_SQL, publicId, userId);
    }

    @Override
    public Optional<AgentConnection> findById(long id) {
        return queryOne(FIND_BY_ID_SQL, id);
    }

    @Override
    public long insert(UUID publicId, long userId, long oauthClientId, String grantedScopesJson, Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                publicId,
                userId,
                oauthClientId,
                grantedScopesJson,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public void revoke(long id, Instant now) {
        jdbcTemplate.update(REVOKE_SQL, Timestamp.from(now), Timestamp.from(now), id);
    }

    @Override
    public List<AgentConnectionSummary> findActiveSummariesByUserId(long userId) {
        return jdbcTemplate.query(FIND_ACTIVE_SUMMARIES_BY_USER_ID_SQL, SUMMARY_ROW_MAPPER, userId);
    }

    private Optional<AgentConnection> queryOne(String sql, Object... parameters) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, parameters));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static AgentConnection mapConnection(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp lastUsedAt = resultSet.getTimestamp("last_used_at");
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        return new AgentConnection(
                resultSet.getLong("id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getLong("user_id"),
                resultSet.getLong("oauth_client_id"),
                resultSet.getString("granted_scopes"),
                resultSet.getTimestamp("connected_at").toInstant(),
                lastUsedAt == null ? null : lastUsedAt.toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static AgentConnectionSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp lastUsedAt = resultSet.getTimestamp("last_used_at");
        return new AgentConnectionSummary(
                resultSet.getObject("connection_id", UUID.class),
                resultSet.getString("client_name"),
                resultSet.getString("logo_url"),
                resultSet.getString("granted_scopes"),
                resultSet.getTimestamp("connected_at").toInstant(),
                lastUsedAt == null ? null : lastUsedAt.toInstant());
    }
}
