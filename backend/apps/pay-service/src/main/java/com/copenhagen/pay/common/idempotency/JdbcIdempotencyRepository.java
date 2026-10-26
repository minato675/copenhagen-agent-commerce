package com.copenhagen.pay.common.idempotency;

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
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private static final String FIND_SQL = """
            SELECT id, caller_type, caller_id, service_type, http_method, normalized_path,
                   idempotency_key, request_hash, status, response_status, response_content_type,
                   response_body_encrypted, created_at, completed_at
            FROM pay_idempotency_records
            WHERE caller_type = ? AND caller_id = ? AND service_type = ?
              AND http_method = ? AND normalized_path = ? AND idempotency_key = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO pay_idempotency_records
                (id, caller_type, caller_id, service_type, http_method, normalized_path,
                 idempotency_key, request_hash, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?)
            ON CONFLICT (caller_type, caller_id, service_type, http_method, normalized_path,
                         idempotency_key)
            DO NOTHING
            """;

    private static final String COMPLETE_SQL = """
            UPDATE pay_idempotency_records
            SET status = 'COMPLETED', response_status = ?, response_content_type = ?,
                response_body_encrypted = ?, completed_at = ?
            WHERE caller_type = ? AND caller_id = ? AND service_type = ?
              AND http_method = ? AND normalized_path = ? AND idempotency_key = ?
              AND status = 'PROCESSING'
            """;

    private static final String DELETE_PROCESSING_SQL = """
            DELETE FROM pay_idempotency_records
            WHERE caller_type = ? AND caller_id = ? AND service_type = ?
              AND http_method = ? AND normalized_path = ? AND idempotency_key = ?
              AND status = 'PROCESSING'
            """;

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER =
            JdbcIdempotencyRepository::mapRecord;

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<IdempotencyRecord> findByScope(IdempotencyScope scope) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_SQL,
                    ROW_MAPPER,
                    scope.callerType(),
                    scope.callerId(),
                    scope.serviceType(),
                    scope.httpMethod(),
                    scope.normalizedPath(),
                    scope.idempotencyKey()));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean insert(IdempotencyRecord record) {
        IdempotencyScope scope = record.scope();
        return jdbcTemplate.update(
                INSERT_SQL,
                record.id(),
                scope.callerType(),
                scope.callerId(),
                scope.serviceType(),
                scope.httpMethod(),
                scope.normalizedPath(),
                scope.idempotencyKey(),
                record.requestHash(),
                Timestamp.from(record.createdAt())) == 1;
    }

    @Override
    public boolean complete(
            IdempotencyScope scope,
            int responseStatus,
            String responseContentType,
            String responseBodyEncrypted,
            Instant completedAt) {
        return jdbcTemplate.update(
                COMPLETE_SQL,
                responseStatus,
                responseContentType,
                responseBodyEncrypted,
                Timestamp.from(completedAt),
                scope.callerType(),
                scope.callerId(),
                scope.serviceType(),
                scope.httpMethod(),
                scope.normalizedPath(),
                scope.idempotencyKey()) == 1;
    }

    @Override
    public boolean deleteProcessing(IdempotencyScope scope) {
        return jdbcTemplate.update(
                DELETE_PROCESSING_SQL,
                scope.callerType(),
                scope.callerId(),
                scope.serviceType(),
                scope.httpMethod(),
                scope.normalizedPath(),
                scope.idempotencyKey()) == 1;
    }

    private static IdempotencyRecord mapRecord(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        return new IdempotencyRecord(
                resultSet.getString("id"),
                new IdempotencyScope(
                        resultSet.getString("caller_type"),
                        resultSet.getString("caller_id"),
                        resultSet.getString("service_type"),
                        resultSet.getString("http_method"),
                        resultSet.getString("normalized_path"),
                        resultSet.getString("idempotency_key")),
                resultSet.getString("request_hash"),
                IdempotencyRecord.Status.valueOf(resultSet.getString("status")),
                resultSet.getObject("response_status", Integer.class),
                resultSet.getString("response_content_type"),
                resultSet.getString("response_body_encrypted"),
                toInstant(resultSet.getTimestamp("created_at")),
                toInstant(resultSet.getTimestamp("completed_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
