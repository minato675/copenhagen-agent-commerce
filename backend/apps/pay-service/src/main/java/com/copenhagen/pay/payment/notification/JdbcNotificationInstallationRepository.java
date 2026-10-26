package com.copenhagen.pay.payment.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationInstallationRepository implements NotificationInstallationRepository {

    private static final String LOCK_FID_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";

    private static final String REBIND_SQL = """
            UPDATE pay_notification_installations
            SET invalidated_at = ?, updated_at = ?
            WHERE fid = ? AND user_id <> ? AND invalidated_at IS NULL
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO pay_notification_installations
                (id, user_id, fid, last_seen_at, invalidated_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, NULL, ?, ?)
            ON CONFLICT (user_id, fid) DO UPDATE
            SET last_seen_at = EXCLUDED.last_seen_at,
                invalidated_at = NULL,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String DEACTIVATE_SQL = """
            UPDATE pay_notification_installations
            SET invalidated_at = ?, updated_at = ?
            WHERE user_id = ? AND fid = ? AND invalidated_at IS NULL
            """;

    private static final String FIND_ACTIVE_FIDS_SQL = """
            SELECT fid
            FROM pay_notification_installations
            WHERE user_id = ? AND invalidated_at IS NULL
            ORDER BY last_seen_at DESC, fid
            """;

    private static final String EXISTS_ACTIVE_FOR_ANOTHER_USER_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM pay_notification_installations
                WHERE fid = ? AND user_id <> ? AND invalidated_at IS NULL
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationInstallationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void registerActive(String userId, String fid, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        // Serialize rebinding of the same FID so the partial unique index remains deterministic.
        jdbcTemplate.queryForList(LOCK_FID_SQL, fid);
        jdbcTemplate.update(REBIND_SQL, timestamp, timestamp, fid, userId);
        jdbcTemplate.update(
                UPSERT_SQL,
                UUID.randomUUID().toString(),
                userId,
                fid,
                timestamp,
                timestamp,
                timestamp);
    }

    @Override
    public int deactivate(String userId, String fid, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbcTemplate.queryForList(LOCK_FID_SQL, fid);
        return jdbcTemplate.update(DEACTIVATE_SQL, timestamp, timestamp, userId, fid);
    }

    @Override
    public boolean existsActiveForAnotherUser(String userId, String fid) {
        Boolean exists = jdbcTemplate.queryForObject(
                EXISTS_ACTIVE_FOR_ANOTHER_USER_SQL,
                Boolean.class,
                fid,
                userId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<String> findActiveFidsByUserId(String userId) {
        return jdbcTemplate.queryForList(FIND_ACTIVE_FIDS_SQL, String.class, userId);
    }
}
