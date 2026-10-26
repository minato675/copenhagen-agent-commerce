package com.copenhagen.core.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseRequestRepository implements PurchaseRequestRepository {

    private static final String INSERT_SQL = """
            INSERT INTO purchase_requests
                (public_id, user_id, cart_id, request_channel, status, policy_version, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String INSERT_PENDING_ADDRESS_SQL = """
            INSERT INTO purchase_requests
                (public_id, user_id, cart_id, agent_connection_id, request_channel, status, policy_version,
                    cart_version, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE purchase_requests
            SET status = ?, updated_at = ?
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcPurchaseRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PurchaseRequest insertCreatingCheckouts(
            UUID publicId,
            long userId,
            long cartId,
            String requestChannel,
            String policyVersion,
            Instant now) {
        Long id = jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                publicId,
                userId,
                cartId,
                requestChannel,
                PurchaseRequest.STATUS_CREATING_CHECKOUTS,
                policyVersion,
                Timestamp.from(now));
        return new PurchaseRequest(
                id == null ? 0L : id,
                publicId,
                userId,
                cartId,
                PurchaseRequest.STATUS_CREATING_CHECKOUTS);
    }

    @Override
    public PurchaseRequest insertPendingAddress(
            UUID publicId,
            long userId,
            long cartId,
            long agentConnectionId,
            String policyVersion,
            int cartVersion,
            Instant expiresAt,
            Instant now) {
        Long id = jdbcTemplate.queryForObject(
                INSERT_PENDING_ADDRESS_SQL,
                Long.class,
                publicId,
                userId,
                cartId,
                agentConnectionId,
                PurchaseRequest.CHANNEL_MCP,
                PurchaseRequest.STATUS_PENDING_ADDRESS,
                policyVersion,
                cartVersion,
                Timestamp.from(expiresAt),
                Timestamp.from(now));
        return new PurchaseRequest(
                id == null ? 0L : id,
                publicId,
                userId,
                cartId,
                PurchaseRequest.STATUS_PENDING_ADDRESS);
    }

    @Override
    public void markFailed(long id, Instant now) {
        jdbcTemplate.update(MARK_FAILED_SQL, PurchaseRequest.STATUS_FAILED, Timestamp.from(now), id);
    }
}
