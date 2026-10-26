package com.copenhagen.core.auth.localaccount;

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
public class JdbcLocalAccountRepository implements LocalAccountRepository {

    private static final String EXISTS_BY_LOGIN_ID_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM local_accounts WHERE login_id = ?
            )
            """;

    private static final String FIND_BY_LOGIN_ID_SQL = """
            SELECT id, user_id, login_id, password_hash,
                failed_login_count, last_failed_login_at, locked_until,
                created_at, updated_at
            FROM local_accounts
            WHERE login_id = ?
            """;

    private static final String FIND_BY_USER_ID_SQL = """
            SELECT id, user_id, login_id, password_hash,
                failed_login_count, last_failed_login_at, locked_until,
                created_at, updated_at
            FROM local_accounts
            WHERE user_id = ?
            """;

    private static final String FIND_BY_ID_FOR_UPDATE_SQL = """
            SELECT id, user_id, login_id, password_hash,
                failed_login_count, last_failed_login_at, locked_until,
                created_at, updated_at
            FROM local_accounts
            WHERE id = ?
            FOR UPDATE
            """;

    private static final String INSERT_SQL = """
            INSERT INTO local_accounts
                (user_id, login_id, password_hash, failed_login_count, created_at, updated_at)
            VALUES (?, ?, ?, 0, ?, ?)
            RETURNING id
            """;

    private static final String RECORD_LOGIN_FAILURE_SQL = """
            UPDATE local_accounts
            SET failed_login_count = ?, last_failed_login_at = ?, locked_until = ?, updated_at = ?
            WHERE id = ?
            """;

    private static final String RESET_LOGIN_FAILURE_SQL = """
            UPDATE local_accounts
            SET failed_login_count = 0, locked_until = NULL, updated_at = ?
            WHERE id = ?
            """;

    private static final RowMapper<LocalAccount> ROW_MAPPER = JdbcLocalAccountRepository::mapLocalAccount;

    private final JdbcTemplate jdbcTemplate;

    public JdbcLocalAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsByLoginId(String loginId) {
        Boolean exists = jdbcTemplate.queryForObject(EXISTS_BY_LOGIN_ID_SQL, Boolean.class, loginId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<LocalAccount> findByLoginId(String loginId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_BY_LOGIN_ID_SQL, ROW_MAPPER, loginId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<LocalAccount> findByUserId(long userId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_BY_USER_ID_SQL, ROW_MAPPER, userId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<LocalAccount> findByIdForUpdate(long id) {
        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(FIND_BY_ID_FOR_UPDATE_SQL, ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public long insert(long userId, String loginId, String passwordHash, Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                userId,
                loginId,
                passwordHash,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public void recordLoginFailure(
            long id, int failedLoginCount, Instant lastFailedLoginAt, Instant lockedUntil, Instant now) {
        jdbcTemplate.update(
                RECORD_LOGIN_FAILURE_SQL,
                failedLoginCount,
                Timestamp.from(lastFailedLoginAt),
                lockedUntil == null ? null : Timestamp.from(lockedUntil),
                Timestamp.from(now),
                id);
    }

    @Override
    public void resetLoginFailure(long id, Instant now) {
        jdbcTemplate.update(RESET_LOGIN_FAILURE_SQL, Timestamp.from(now), id);
    }

    private static LocalAccount mapLocalAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp lastFailedLoginAt = resultSet.getTimestamp("last_failed_login_at");
        Timestamp lockedUntil = resultSet.getTimestamp("locked_until");
        return new LocalAccount(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("login_id"),
                resultSet.getString("password_hash"),
                resultSet.getInt("failed_login_count"),
                lastFailedLoginAt == null ? null : lastFailedLoginAt.toInstant(),
                lockedUntil == null ? null : lockedUntil.toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
