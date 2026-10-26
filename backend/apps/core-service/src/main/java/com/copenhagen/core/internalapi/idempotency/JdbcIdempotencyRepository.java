package com.copenhagen.core.internalapi.idempotency;

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
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private static final String FIND_SQL = """
            SELECT request_hash, response_status, response_body::text AS response_body
            FROM internal_idempotency_keys
            WHERE user_id = ? AND idempotency_key = ? AND expires_at > ?
            """;

    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM internal_idempotency_keys
            WHERE user_id = ? AND idempotency_key = ? AND expires_at <= ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO internal_idempotency_keys
                (user_id, idempotency_key, method, path, request_hash, response_status, response_body,
                    created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = JdbcIdempotencyRepository::mapRecord;

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<IdempotencyRecord> find(long userId, UUID idempotencyKey, Instant now) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_SQL, ROW_MAPPER, userId, idempotencyKey, Timestamp.from(now)));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public int deleteExpired(long userId, UUID idempotencyKey, Instant now) {
        return jdbcTemplate.update(DELETE_EXPIRED_SQL, userId, idempotencyKey, Timestamp.from(now));
    }

    @Override
    public void insert(
            long userId,
            UUID idempotencyKey,
            String method,
            String path,
            String requestHash,
            int responseStatus,
            String responseBody,
            Instant now,
            Instant expiresAt) {
        jdbcTemplate.update(
                INSERT_SQL,
                userId,
                idempotencyKey,
                method,
                path,
                requestHash,
                responseStatus,
                responseBody,
                Timestamp.from(now),
                Timestamp.from(expiresAt));
    }

    private static IdempotencyRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IdempotencyRecord(
                resultSet.getString("request_hash"),
                resultSet.getInt("response_status"),
                resultSet.getString("response_body"));
    }
}
