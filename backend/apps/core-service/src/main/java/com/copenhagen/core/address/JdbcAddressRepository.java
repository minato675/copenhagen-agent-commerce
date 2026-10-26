package com.copenhagen.core.address;

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

import com.copenhagen.core.common.crypto.EncryptedPii;

@Repository
public class JdbcAddressRepository implements AddressRepository {

    private static final String COUNT_ACTIVE_BY_USER_ID_SQL = """
            SELECT COUNT(*) FROM addresses WHERE user_id = ? AND deleted_at IS NULL
            """;

    private static final String INSERT_SQL = """
            INSERT INTO addresses
                (public_id, user_id, recipient_name_encrypted, recipient_phone_encrypted, postal_code_encrypted,
                    address_line1_encrypted, address_line2_encrypted, delivery_request_encrypted, is_default,
                    created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String SELECT_COLUMNS = """
            SELECT id, public_id, user_id, recipient_name_encrypted, recipient_phone_encrypted,
                postal_code_encrypted, address_line1_encrypted, address_line2_encrypted,
                delivery_request_encrypted, is_default, created_at, updated_at
            FROM addresses
            """;

    private static final String FIND_ALL_ACTIVE_BY_USER_ID_SQL = SELECT_COLUMNS + """
            WHERE user_id = ? AND deleted_at IS NULL
            ORDER BY is_default DESC, created_at DESC
            """;

    private static final String FIND_ACTIVE_BY_PUBLIC_ID_AND_USER_ID_SQL = SELECT_COLUMNS + """
            WHERE public_id = ? AND user_id = ? AND deleted_at IS NULL
            """;

    private static final String UPDATE_SQL = """
            UPDATE addresses
            SET recipient_name_encrypted = ?, recipient_phone_encrypted = ?, postal_code_encrypted = ?,
                address_line1_encrypted = ?, address_line2_encrypted = ?, delivery_request_encrypted = ?,
                updated_at = ?
            WHERE id = ?
            """;

    private static final String SOFT_DELETE_SQL = """
            UPDATE addresses
            SET deleted_at = ?, updated_at = ?
            WHERE id = ?
            """;

    private static final String CLEAR_DEFAULT_SQL = """
            UPDATE addresses
            SET is_default = FALSE, updated_at = ?
            WHERE user_id = ? AND is_default = TRUE AND deleted_at IS NULL
            """;

    private static final String SET_DEFAULT_SQL = """
            UPDATE addresses
            SET is_default = TRUE, updated_at = ?
            WHERE id = ?
            """;

    private static final RowMapper<Address> ROW_MAPPER = JdbcAddressRepository::mapAddress;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAddressRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int countActiveByUserId(long userId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_ACTIVE_BY_USER_ID_SQL, Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Override
    public long insert(
            UUID publicId,
            long userId,
            boolean isDefault,
            EncryptedPii recipientNameEncrypted,
            EncryptedPii recipientPhoneEncrypted,
            EncryptedPii postalCodeEncrypted,
            EncryptedPii addressLine1Encrypted,
            EncryptedPii addressLine2Encrypted,
            EncryptedPii deliveryRequestEncrypted,
            Instant now) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                publicId,
                userId,
                recipientNameEncrypted.toByteArray(),
                recipientPhoneEncrypted.toByteArray(),
                postalCodeEncrypted.toByteArray(),
                addressLine1Encrypted.toByteArray(),
                addressLine2Encrypted == null ? null : addressLine2Encrypted.toByteArray(),
                deliveryRequestEncrypted == null ? null : deliveryRequestEncrypted.toByteArray(),
                isDefault,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public List<Address> findAllActiveByUserId(long userId) {
        return jdbcTemplate.query(FIND_ALL_ACTIVE_BY_USER_ID_SQL, ROW_MAPPER, userId);
    }

    @Override
    public Optional<Address> findActiveByPublicIdAndUserId(UUID publicId, long userId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    FIND_ACTIVE_BY_PUBLIC_ID_AND_USER_ID_SQL, ROW_MAPPER, publicId, userId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void update(
            long id,
            EncryptedPii recipientNameEncrypted,
            EncryptedPii recipientPhoneEncrypted,
            EncryptedPii postalCodeEncrypted,
            EncryptedPii addressLine1Encrypted,
            EncryptedPii addressLine2Encrypted,
            EncryptedPii deliveryRequestEncrypted,
            Instant now) {
        jdbcTemplate.update(
                UPDATE_SQL,
                recipientNameEncrypted.toByteArray(),
                recipientPhoneEncrypted.toByteArray(),
                postalCodeEncrypted.toByteArray(),
                addressLine1Encrypted.toByteArray(),
                addressLine2Encrypted == null ? null : addressLine2Encrypted.toByteArray(),
                deliveryRequestEncrypted == null ? null : deliveryRequestEncrypted.toByteArray(),
                Timestamp.from(now),
                id);
    }

    @Override
    public void softDelete(long id, Instant now) {
        jdbcTemplate.update(SOFT_DELETE_SQL, Timestamp.from(now), Timestamp.from(now), id);
    }

    @Override
    public void clearDefault(long userId, Instant now) {
        jdbcTemplate.update(CLEAR_DEFAULT_SQL, Timestamp.from(now), userId);
    }

    @Override
    public void setDefault(long id, Instant now) {
        jdbcTemplate.update(SET_DEFAULT_SQL, Timestamp.from(now), id);
    }

    private static Address mapAddress(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Address(
                resultSet.getLong("id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getLong("user_id"),
                EncryptedPii.of(resultSet.getBytes("recipient_name_encrypted")),
                EncryptedPii.of(resultSet.getBytes("recipient_phone_encrypted")),
                EncryptedPii.of(resultSet.getBytes("postal_code_encrypted")),
                EncryptedPii.of(resultSet.getBytes("address_line1_encrypted")),
                EncryptedPii.of(resultSet.getBytes("address_line2_encrypted")),
                EncryptedPii.of(resultSet.getBytes("delivery_request_encrypted")),
                resultSet.getBoolean("is_default"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
