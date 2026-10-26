package com.copenhagen.core.agent.authorize;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOauthAuthorizationRequestRepository implements OauthAuthorizationRequestRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, request_id, oauth_client_id, redirect_uri, requested_scopes::text AS requested_scopes,
                state, code_challenge, code_challenge_method, status, user_id, code_hash, code_expires_at,
                request_expires_at, created_at, updated_at
            FROM oauth_authorization_requests
            """;

    private static final String INSERT_SQL = """
            INSERT INTO oauth_authorization_requests
                (request_id, oauth_client_id, redirect_uri, requested_scopes, state, code_challenge,
                    code_challenge_method, request_expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String FIND_BY_REQUEST_ID_SQL = SELECT_COLUMNS + " WHERE request_id = ?";

    private static final String FIND_BY_CODE_HASH_FOR_UPDATE_SQL = SELECT_COLUMNS
            + " WHERE code_hash = ? FOR UPDATE\n";

    private static final String MARK_APPROVED_SQL = """
            UPDATE oauth_authorization_requests
            SET status = 'APPROVED', user_id = ?, code_hash = ?, code_expires_at = ?, updated_at = ?
            WHERE id = ? AND status = 'PENDING'
            """;

    private static final String MARK_DENIED_SQL = """
            UPDATE oauth_authorization_requests
            SET status = 'DENIED', updated_at = ?
            WHERE id = ? AND status = 'PENDING'
            """;

    private static final String MARK_CONSUMED_SQL = """
            UPDATE oauth_authorization_requests
            SET status = 'CONSUMED', updated_at = ?
            WHERE id = ? AND status = 'APPROVED'
            """;

    private static final RowMapper<OauthAuthorizationRequest> ROW_MAPPER =
            JdbcOauthAuthorizationRequestRepository::mapRequest;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOauthAuthorizationRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(
            UUID requestId,
            long oauthClientId,
            String redirectUri,
            String requestedScopesJson,
            String state,
            String codeChallenge,
            String codeChallengeMethod,
            Instant requestExpiresAt,
            Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                requestId,
                oauthClientId,
                redirectUri,
                requestedScopesJson,
                state,
                codeChallenge,
                codeChallengeMethod,
                Timestamp.from(requestExpiresAt),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public Optional<OauthAuthorizationRequest> findByRequestId(UUID requestId) {
        return queryOne(FIND_BY_REQUEST_ID_SQL, requestId);
    }

    @Override
    public Optional<OauthAuthorizationRequest> findByCodeHashForUpdate(String codeHash) {
        return queryOne(FIND_BY_CODE_HASH_FOR_UPDATE_SQL, codeHash);
    }

    @Override
    public int markApproved(long id, long userId, String codeHash, Instant codeExpiresAt, Instant now) {
        return jdbcTemplate.update(
                MARK_APPROVED_SQL, userId, codeHash, Timestamp.from(codeExpiresAt), Timestamp.from(now), id);
    }

    @Override
    public int markDenied(long id, Instant now) {
        return jdbcTemplate.update(MARK_DENIED_SQL, Timestamp.from(now), id);
    }

    @Override
    public int markConsumed(long id, Instant now) {
        return jdbcTemplate.update(MARK_CONSUMED_SQL, Timestamp.from(now), id);
    }

    private Optional<OauthAuthorizationRequest> queryOne(String sql, Object parameter) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, parameter));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static OauthAuthorizationRequest mapRequest(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp codeExpiresAt = resultSet.getTimestamp("code_expires_at");
        long userId = resultSet.getLong("user_id");
        Long userIdOrNull = resultSet.wasNull() ? null : userId;
        return new OauthAuthorizationRequest(
                resultSet.getLong("id"),
                resultSet.getObject("request_id", UUID.class),
                resultSet.getLong("oauth_client_id"),
                resultSet.getString("redirect_uri"),
                resultSet.getString("requested_scopes"),
                resultSet.getString("state"),
                resultSet.getString("code_challenge"),
                resultSet.getString("code_challenge_method"),
                resultSet.getString("status"),
                userIdOrNull,
                resultSet.getString("code_hash"),
                codeExpiresAt == null ? null : codeExpiresAt.toInstant(),
                resultSet.getTimestamp("request_expires_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
