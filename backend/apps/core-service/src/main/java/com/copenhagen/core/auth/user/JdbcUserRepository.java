package com.copenhagen.core.auth.user;

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

import com.copenhagen.core.common.crypto.EncryptedPii;

@Repository
public class JdbcUserRepository implements UserRepository {

    private static final String INSERT_SQL = """
            INSERT INTO users (public_id, name, status, email_encrypted, email_hash, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String SELECT_COLUMNS = """
            SELECT id, public_id, name, status, email_encrypted, email_hash, email_verified,
                phone_encrypted, phone_hash, deleted_at, pw_changed_at, last_login_at, created_at, updated_at
            FROM users
            """;

    private static final String FIND_BY_ID_SQL = SELECT_COLUMNS + "WHERE id = ?";

    private static final String FIND_BY_PUBLIC_ID_SQL = SELECT_COLUMNS + "WHERE public_id = ?";

    private static final String EXISTS_BY_EMAIL_HASH_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM users WHERE email_hash = ? AND deleted_at IS NULL
            )
            """;

    private static final String UPDATE_LAST_LOGIN_AT_SQL = """
            UPDATE users
            SET last_login_at = ?, updated_at = ?
            WHERE id = ?
            """;

    private static final RowMapper<User> ROW_MAPPER = JdbcUserRepository::mapUser;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(UUID publicId, String name, UserStatus status,
            EncryptedPii emailEncrypted, String emailHash, Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                publicId,
                name,
                status.name(),
                emailEncrypted == null ? null : emailEncrypted.toByteArray(),
                emailHash,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public boolean existsByEmailHash(String emailHash) {
        Boolean exists = jdbcTemplate.queryForObject(EXISTS_BY_EMAIL_HASH_SQL, Boolean.class, emailHash);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<User> findById(long id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_BY_ID_SQL, ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<User> findByPublicId(UUID publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(FIND_BY_PUBLIC_ID_SQL, ROW_MAPPER, publicId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void updateLastLoginAt(long id, Instant now) {
        jdbcTemplate.update(UPDATE_LAST_LOGIN_AT_SQL, Timestamp.from(now), Timestamp.from(now), id);
    }

    private static User mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp deletedAt = resultSet.getTimestamp("deleted_at");
        Timestamp pwChangedAt = resultSet.getTimestamp("pw_changed_at");
        Timestamp lastLoginAt = resultSet.getTimestamp("last_login_at");
        return new User(
                resultSet.getLong("id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getString("name"),
                UserStatus.valueOf(resultSet.getString("status")),
                EncryptedPii.of(resultSet.getBytes("email_encrypted")),
                trimmed(resultSet.getString("email_hash")),
                resultSet.getBoolean("email_verified"),
                EncryptedPii.of(resultSet.getBytes("phone_encrypted")),
                trimmed(resultSet.getString("phone_hash")),
                deletedAt == null ? null : deletedAt.toInstant(),
                pwChangedAt == null ? null : pwChangedAt.toInstant(),
                lastLoginAt == null ? null : lastLoginAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    /** CHAR(64)는 공백으로 채워져 돌아오므로 잘라낸다. 64자 해시는 영향을 받지 않는다. */
    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
