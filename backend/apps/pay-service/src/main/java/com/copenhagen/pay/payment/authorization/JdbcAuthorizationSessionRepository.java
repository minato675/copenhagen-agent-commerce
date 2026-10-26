package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionItemStatus;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthorizationSessionRepository
        implements AuthorizationSessionRepository,
        AuthorizationSessionItemRepository,
        AuthorizationSessionStore {

    private static final String FIND_STATUS_SQL = """
            SELECT status
            FROM authorization_sessions
            WHERE id = ?
            """;

    private static final String TRANSITION_STATUS_SQL = """
            UPDATE authorization_sessions
            SET status = ?, version = version + 1
            WHERE id = ? AND status = ?
            """;

    private static final String FIND_ITEM_STATUS_SQL = """
            SELECT status
            FROM authorization_session_items
            WHERE id = ?
            """;

    private static final String TRANSITION_ITEM_STATUS_SQL = """
            UPDATE authorization_session_items
            SET status = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
            WHERE id = ? AND status = ?
            """;

    private static final String APPROVE_SQL = """
            UPDATE authorization_sessions
            SET status = 'APPROVED', auth_method = ?, approved_at = ?, updated_at = ?,
                version = version + 1
            WHERE id = ? AND user_id = ? AND status = 'PENDING' AND expires_at > ?
            """;

    private static final String REJECT_SQL = """
            UPDATE authorization_sessions
            SET status = 'REJECTED', updated_at = ?, version = version + 1
            WHERE id = ? AND user_id = ?
              AND status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING')
              AND expires_at > ?
            """;

    private static final String SET_REQUIRED_ACTION_SQL = """
            UPDATE authorization_sessions
            SET required_action = ?, updated_at = ?, version = version + 1
            WHERE id = ? AND user_id = ? AND status = ? AND expires_at > ?
            """;

    private static final String FALLBACK_PENDING_TO_PIN_SQL = """
            UPDATE authorization_sessions
            SET required_action = 'PIN', updated_at = ?, version = version + 1
            WHERE user_id = ?
              AND status = 'PENDING'
              AND required_action = 'BIOMETRIC'
              AND expires_at > ?
            """;

    private static final String SELECT_PAYMENT_METHOD_SQL = """
            UPDATE authorization_sessions
            SET selected_payment_method_id = ?,
                status = CASE
                    WHEN status = 'PAYMENT_METHOD_REQUIRED' THEN 'PENDING'
                    ELSE status
                END,
                updated_at = ?, version = version + 1
            WHERE id = ? AND user_id = ?
              AND status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING')
              AND expires_at > ?
            """;

    private static final String SELECT_BASE_SQL = """
            SELECT s.id, s.purchase_request_id, s.user_id,
                   s.total_amount_minor, s.currency, s.selected_payment_method_id,
                   s.status, s.idempotency_key, s.request_hash, s.required_action,
                   s.expires_at, s.approved_at, s.auth_method,
                   s.created_at, s.updated_at, s.version
            FROM authorization_sessions s
            """;

    private static final String FIND_BY_IDEMPOTENCY_SQL = SELECT_BASE_SQL + """
            WHERE s.user_id = ? AND s.idempotency_key = ?
            """;

    private static final String FIND_ACTIVE_BY_PURCHASE_REQUEST_SQL = SELECT_BASE_SQL + """
            WHERE s.purchase_request_id = ?
              AND s.status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
            """;

    private static final String FIND_BY_USER_SQL = SELECT_BASE_SQL + """
            WHERE s.user_id = ?
              AND s.status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
            ORDER BY s.created_at DESC
            """;

    private static final String FIND_BY_USER_AND_STATUS_SQL = SELECT_BASE_SQL + """
            WHERE s.user_id = ? AND s.status = ?
            ORDER BY s.created_at DESC
            """;

    private static final String FIND_BY_ID_AND_USER_SQL = SELECT_BASE_SQL + """
            WHERE s.id = ? AND s.user_id = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO authorization_sessions
                (id, purchase_request_id, user_id, total_amount_minor, currency,
                 selected_payment_method_id, status, idempotency_key, request_hash,
                 required_action, expires_at, approved_at, auth_method,
                 created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String INSERT_ITEM_SQL = """
            INSERT INTO authorization_session_items
                (id, authorization_session_id, merchant_account_id, external_checkout_id,
                 checkout_digest, display_snapshot, amount_minor, currency, status,
                 created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ITEM_BASE_SQL = """
            SELECT i.id, i.authorization_session_id, i.merchant_account_id,
                   m.merchant_id, m.display_name AS merchant_name,
                   i.external_checkout_id, i.checkout_digest,
                   i.display_snapshot::text AS display_snapshot_json,
                   i.amount_minor, i.currency, i.status,
                   i.created_at, i.updated_at, i.version
            FROM authorization_session_items i
            LEFT JOIN merchant_accounts m ON m.id = i.merchant_account_id
            """;

    private static final String EXPIRE_OVERDUE_BY_USER_SQL = """
            UPDATE authorization_sessions
            SET status = 'EXPIRED', version = version + 1
            WHERE user_id = ?
              AND status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
              AND expires_at <= ?
            """;

    private static final String EXPIRE_OVERDUE_BY_ID_AND_USER_SQL = """
            UPDATE authorization_sessions
            SET status = 'EXPIRED', version = version + 1
            WHERE id = ?
              AND user_id = ?
              AND status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
              AND expires_at <= ?
            """;

    /**
     * 만료된 세션의 남은 item 을 함께 정리한다. 단 {@code approved_at IS NULL} 인 세션만
     * 대상으로 한다. 이미 승인된 세션은 credential 이 발급되어 판매처가 결제를 진행 중일 수
     * 있고, 그 item 을 EXPIRED 로 바꾸면 결제 성공을 기록할 수 없게 된다.
     */
    private static final String EXPIRE_ITEMS_FOR_USER_SQL = """
            UPDATE authorization_session_items i
            SET status = 'EXPIRED', updated_at = ?, version = i.version + 1
            FROM authorization_sessions s
            WHERE s.id = i.authorization_session_id
              AND s.user_id = ?
              AND s.status = 'EXPIRED'
              AND s.approved_at IS NULL
              AND i.status = 'PENDING'
            """;

    private static final String EXPIRE_ITEMS_BY_SESSION_SQL = """
            UPDATE authorization_session_items i
            SET status = 'EXPIRED', updated_at = ?, version = i.version + 1
            FROM authorization_sessions s
            WHERE s.id = i.authorization_session_id
              AND s.id = ?
              AND s.user_id = ?
              AND s.status = 'EXPIRED'
              AND s.approved_at IS NULL
              AND i.status = 'PENDING'
            """;

    private static final RowMapper<AuthorizationSession> ROW_MAPPER =
            JdbcAuthorizationSessionRepository::mapAuthorizationSession;

    private static final RowMapper<AuthorizationSessionItem> ITEM_ROW_MAPPER =
            JdbcAuthorizationSessionRepository::mapAuthorizationSessionItem;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthorizationSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AuthorizationSessionStatus> findStatus(String authorizationSessionId) {
        try {
            String status = jdbcTemplate.queryForObject(
                    FIND_STATUS_SQL,
                    String.class,
                    authorizationSessionId);
            return Optional.of(AuthorizationSessionStatus.valueOf(status));
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean transitionStatus(
            String authorizationSessionId,
            AuthorizationSessionStatus expectedStatus,
            AuthorizationSessionStatus targetStatus) {
        int updated = jdbcTemplate.update(
                TRANSITION_STATUS_SQL,
                targetStatus.name(),
                authorizationSessionId,
                expectedStatus.name());
        return updated == 1;
    }

    @Override
    public Optional<AuthorizationSessionItemStatus> findItemStatus(String sessionItemId) {
        try {
            String status = jdbcTemplate.queryForObject(
                    FIND_ITEM_STATUS_SQL,
                    String.class,
                    sessionItemId);
            return Optional.of(AuthorizationSessionItemStatus.valueOf(status));
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean transitionItemStatus(
            String sessionItemId,
            AuthorizationSessionItemStatus expectedStatus,
            AuthorizationSessionItemStatus targetStatus) {
        int updated = jdbcTemplate.update(
                TRANSITION_ITEM_STATUS_SQL,
                targetStatus.name(),
                sessionItemId,
                expectedStatus.name());
        return updated == 1;
    }

    @Override
    public Optional<AuthorizationSession> findByUserAndIdempotencyKey(
            String userId,
            String idempotencyKey) {
        return withItems(jdbcTemplate.query(
                FIND_BY_IDEMPOTENCY_SQL,
                ROW_MAPPER,
                userId,
                idempotencyKey))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<AuthorizationSession> findActiveByPurchaseRequest(String purchaseRequestId) {
        return withItems(jdbcTemplate.query(
                FIND_ACTIVE_BY_PURCHASE_REQUEST_SQL,
                ROW_MAPPER,
                purchaseRequestId))
                .stream()
                .findFirst();
    }

    @Override
    public boolean insert(AuthorizationSession session) {
        int inserted = jdbcTemplate.update(
                INSERT_SQL,
                session.id(),
                session.purchaseRequestId(),
                session.userId(),
                session.totalAmountMinor(),
                session.currency(),
                session.selectedPaymentMethodId(),
                session.status().name(),
                session.idempotencyKey(),
                session.requestHash(),
                session.requiredAction(),
                toTimestamp(session.expiresAt()),
                toTimestamp(session.approvedAt()),
                session.authMethod(),
                toTimestamp(session.createdAt()),
                toTimestamp(session.updatedAt()),
                session.version());
        if (inserted != 1) {
            return false;
        }
        for (AuthorizationSessionItem item : session.items()) {
            jdbcTemplate.update(
                    INSERT_ITEM_SQL,
                    item.id(),
                    session.id(),
                    item.merchantAccountId(),
                    item.externalCheckoutId(),
                    item.checkoutDigest(),
                    item.displaySnapshotJson(),
                    item.amountMinor(),
                    item.currency(),
                    item.status().name(),
                    toTimestamp(item.createdAt()),
                    toTimestamp(item.updatedAt()),
                    item.version());
        }
        return true;
    }

    @Override
    public List<AuthorizationSession> findByUserId(
            String userId,
            AuthorizationSessionStatus status) {
        if (status == null) {
            return withItems(jdbcTemplate.query(FIND_BY_USER_SQL, ROW_MAPPER, userId));
        }
        return withItems(jdbcTemplate.query(
                FIND_BY_USER_AND_STATUS_SQL,
                ROW_MAPPER,
                userId,
                status.name()));
    }

    @Override
    public Optional<AuthorizationSession> findByIdAndUserId(
            String authorizationSessionId,
            String userId) {
        return withItems(jdbcTemplate.query(
                FIND_BY_ID_AND_USER_SQL,
                ROW_MAPPER,
                authorizationSessionId,
                userId))
                .stream()
                .findFirst();
    }

    @Override
    public int expireOverdueForUser(String userId, Instant now) {
        int expired = jdbcTemplate.update(EXPIRE_OVERDUE_BY_USER_SQL, userId, toTimestamp(now));
        jdbcTemplate.update(EXPIRE_ITEMS_FOR_USER_SQL, toTimestamp(now), userId);
        return expired;
    }

    @Override
    public int expireOverdueByIdAndUser(
            String authorizationSessionId,
            String userId,
            Instant now) {
        int expired = jdbcTemplate.update(
                EXPIRE_OVERDUE_BY_ID_AND_USER_SQL,
                authorizationSessionId,
                userId,
                toTimestamp(now));
        jdbcTemplate.update(
                EXPIRE_ITEMS_BY_SESSION_SQL,
                toTimestamp(now),
                authorizationSessionId,
                userId);
        return expired;
    }

    @Override
    public boolean approve(
            String authorizationSessionId,
            String userId,
            String authMethod,
            Instant approvedAt) {
        return jdbcTemplate.update(
                APPROVE_SQL,
                authMethod,
                toTimestamp(approvedAt),
                toTimestamp(approvedAt),
                authorizationSessionId,
                userId,
                toTimestamp(approvedAt)) == 1;
    }

    @Override
    public boolean reject(
            String authorizationSessionId,
            String userId,
            Instant rejectedAt) {
        return jdbcTemplate.update(
                REJECT_SQL,
                toTimestamp(rejectedAt),
                authorizationSessionId,
                userId,
                toTimestamp(rejectedAt)) == 1;
    }

    @Override
    public boolean setRequiredAction(
            String authorizationSessionId,
            String userId,
            AuthorizationSessionStatus expectedStatus,
            String requiredAction,
            Instant now) {
        return jdbcTemplate.update(
                SET_REQUIRED_ACTION_SQL,
                requiredAction,
                toTimestamp(now),
                authorizationSessionId,
                userId,
                expectedStatus.name(),
                toTimestamp(now)) == 1;
    }

    @Override
    public int fallbackPendingToPin(String userId, Instant now) {
        return jdbcTemplate.update(
                FALLBACK_PENDING_TO_PIN_SQL,
                toTimestamp(now),
                userId,
                toTimestamp(now));
    }

    @Override
    public boolean selectPaymentMethod(
            String authorizationSessionId,
            String userId,
            String paymentMethodId,
            Instant now) {
        return jdbcTemplate.update(
                SELECT_PAYMENT_METHOD_SQL,
                paymentMethodId,
                toTimestamp(now),
                authorizationSessionId,
                userId,
                toTimestamp(now)) == 1;
    }

    /** 세션 목록에 판매처별 item 을 한 번의 조회로 붙인다. */
    private List<AuthorizationSession> withItems(List<AuthorizationSession> sessions) {
        if (sessions.isEmpty()) {
            return sessions;
        }
        List<String> sessionIds = sessions.stream().map(AuthorizationSession::id).toList();
        String placeholders = sessionIds.stream()
                .map(id -> "?")
                .collect(Collectors.joining(", "));
        List<AuthorizationSessionItem> items = jdbcTemplate.query(
                SELECT_ITEM_BASE_SQL
                        + " WHERE i.authorization_session_id IN (" + placeholders + ")"
                        + " ORDER BY i.created_at, i.id",
                ITEM_ROW_MAPPER,
                sessionIds.toArray());
        Map<String, List<AuthorizationSessionItem>> bySession = new LinkedHashMap<>();
        for (AuthorizationSessionItem item : items) {
            bySession.computeIfAbsent(item.authorizationSessionId(), key -> new ArrayList<>())
                    .add(item);
        }
        List<AuthorizationSession> result = new ArrayList<>(sessions.size());
        for (AuthorizationSession session : sessions) {
            result.add(new AuthorizationSession(
                    session.id(),
                    session.purchaseRequestId(),
                    session.userId(),
                    session.totalAmountMinor(),
                    session.currency(),
                    session.selectedPaymentMethodId(),
                    session.status(),
                    session.idempotencyKey(),
                    session.requestHash(),
                    session.requiredAction(),
                    session.expiresAt(),
                    session.approvedAt(),
                    session.authMethod(),
                    session.createdAt(),
                    session.updatedAt(),
                    session.version(),
                    bySession.getOrDefault(session.id(), List.of())));
        }
        return result;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static AuthorizationSession mapAuthorizationSession(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        return new AuthorizationSession(
                resultSet.getString("id"),
                resultSet.getString("purchase_request_id"),
                resultSet.getString("user_id"),
                resultSet.getLong("total_amount_minor"),
                resultSet.getString("currency"),
                resultSet.getString("selected_payment_method_id"),
                AuthorizationSessionStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("idempotency_key"),
                resultSet.getString("request_hash"),
                resultSet.getString("required_action"),
                getInstant(resultSet, "expires_at"),
                getInstant(resultSet, "approved_at"),
                resultSet.getString("auth_method"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at"),
                resultSet.getInt("version"),
                List.of());
    }

    private static AuthorizationSessionItem mapAuthorizationSessionItem(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        return new AuthorizationSessionItem(
                resultSet.getString("id"),
                resultSet.getString("authorization_session_id"),
                resultSet.getString("merchant_account_id"),
                resultSet.getString("merchant_id"),
                resultSet.getString("merchant_name"),
                resultSet.getString("external_checkout_id"),
                resultSet.getString("checkout_digest"),
                resultSet.getString("display_snapshot_json"),
                resultSet.getLong("amount_minor"),
                resultSet.getString("currency"),
                AuthorizationSessionItemStatus.valueOf(resultSet.getString("status")),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at"),
                resultSet.getInt("version"));
    }

    private static Instant getInstant(
            ResultSet resultSet,
            String columnName) throws SQLException {
        OffsetDateTime value = resultSet.getObject(columnName, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
