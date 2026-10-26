package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.copenhagen.contract.internalapi.PaymentStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** checkout_sessions 를 읽고 쓴다. JdbcTemplate 만 쓴다(DESIGN.md 4절). */
@Repository
public class JdbcCheckoutRepository implements CheckoutRepository {

    private static final String INSERT = """
            INSERT INTO checkout_sessions
                (id, merchant_id, status, currency, total_amount, fulfillment_address, messages_json,
                 line_skus_json, canonical_checkout, merchant_authorization_jws, expires_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
            """;

    private static final String SELECT = """
            SELECT id, merchant_id, status, currency, total_amount, fulfillment_address::text, messages_json::text,
                   line_skus_json::text, canonical_checkout, merchant_authorization_jws, expires_at,
                   completion_response_json::text, payment_status, pay_payment_id, version
              FROM checkout_sessions
             WHERE id = ?
            """;

    private static final String UPDATE = """
            UPDATE checkout_sessions
               SET status = ?, currency = ?, total_amount = ?, fulfillment_address = ?::jsonb,
                   messages_json = ?::jsonb, line_skus_json = ?::jsonb, canonical_checkout = ?,
                   merchant_authorization_jws = ?, expires_at = ?, version = version + 1, updated_at = now()
             WHERE id = ? AND version = ?
            """;

    private static final String BEGIN_COMPLETE = """
            UPDATE checkout_sessions
               SET status = 'COMPLETE_IN_PROGRESS', version = version + 1, updated_at = now()
             WHERE id = ? AND status = 'READY_FOR_COMPLETE' AND expires_at > ?
            """;

    private static final String FINISH_COMPLETE = """
            UPDATE checkout_sessions
               SET status = 'COMPLETED', completion_response_json = ?::jsonb,
                   version = version + 1, updated_at = now()
             WHERE id = ? AND status = 'COMPLETE_IN_PROGRESS'
            """;

    private static final String REVERT_COMPLETE = """
            UPDATE checkout_sessions
               SET status = 'READY_FOR_COMPLETE', version = version + 1, updated_at = now()
             WHERE id = ? AND status = 'COMPLETE_IN_PROGRESS'
            """;

    private static final String CANCEL = """
            UPDATE checkout_sessions
               SET status = 'CANCELED', version = version + 1, updated_at = now()
             WHERE id = ?
               AND status IN ('INCOMPLETE', 'REQUIRES_ESCALATION', 'READY_FOR_COMPLETE')
            """;

    private static final String EXPIRE = """
            UPDATE checkout_sessions
               SET status = 'CANCELED', version = version + 1, updated_at = now()
             WHERE id IN (SELECT id
                            FROM checkout_sessions
                           WHERE status = 'READY_FOR_COMPLETE'
                             AND expires_at < ?
                           ORDER BY expires_at
                           LIMIT ?)
               AND status = 'READY_FOR_COMPLETE'
            """;

    private static final String CLAIM_UNKNOWN_DUE = """
            UPDATE checkout_sessions
               SET payment_next_attempt_at = ?,
                   payment_attempt_count   = payment_attempt_count + 1,
                   updated_at              = now()
             WHERE id IN (SELECT id
                            FROM checkout_sessions
                           WHERE payment_status = 'UNKNOWN'
                             AND payment_next_attempt_at <= ?
                             AND payment_attempt_count < ?
                           ORDER BY payment_next_attempt_at
                           LIMIT ?)
               AND payment_status = 'UNKNOWN'
            RETURNING id, merchant_id, pay_payment_id, payment_credential_id, merchant_authorization_jws,
                      total_amount, currency, payment_attempt_count
            """;

    private static final String RESOLVE_PAYMENT = """
            UPDATE checkout_sessions
               SET payment_status = ?, pay_payment_id = COALESCE(?, pay_payment_id),
                   payment_next_attempt_at = NULL, updated_at = now()
             WHERE id = ? AND payment_status = 'UNKNOWN'
            """;

    private static final String SCHEDULE_NEXT_RECHECK = """
            UPDATE checkout_sessions
               SET payment_next_attempt_at = ?, updated_at = now()
             WHERE id = ? AND payment_status = 'UNKNOWN'
            """;

    private static final String BEGIN_PAYMENT = """
            UPDATE checkout_sessions
               SET payment_status = 'PROCESSING', payment_requested_at = ?,
                   payment_credential_id = ?, payment_next_attempt_at = ?,
                   version = version + 1, updated_at = now()
             WHERE id = ? AND (payment_status IS NULL OR payment_status = 'FAILED')
            """;

    private static final String FINISH_PAYMENT = """
            UPDATE checkout_sessions
               SET payment_status = ?, pay_payment_id = COALESCE(?, pay_payment_id),
                   version = version + 1, updated_at = now()
             WHERE id = ? AND payment_status = 'PROCESSING'
            """;

    private static final RowMapper<PaymentRecheck> RECHECK_MAPPER = (rs, rowNum) -> new PaymentRecheck(
            rs.getString("id"),
            rs.getString("merchant_id"),
            rs.getString("pay_payment_id"),
            rs.getString("payment_credential_id"),
            rs.getString("merchant_authorization_jws"),
            rs.getObject("total_amount", Long.class),
            rs.getString("currency"),
            rs.getInt("payment_attempt_count"));

    private static final RowMapper<CheckoutSession> ROW_MAPPER = (rs, rowNum) -> new CheckoutSession(
            rs.getString("id"),
            rs.getString("merchant_id"),
            CheckoutStatus.valueOf(rs.getString("status")),
            rs.getString("currency"),
            rs.getObject("total_amount", Long.class),
            rs.getString("fulfillment_address"),
            rs.getString("messages_json"),
            rs.getString("line_skus_json"),
            rs.getString("canonical_checkout"),
            rs.getString("merchant_authorization_jws"),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getString("completion_response_json"),
            paymentStatusOf(rs.getString("payment_status")),
            rs.getString("pay_payment_id"),
            rs.getLong("version"));

    private static PaymentStatus paymentStatusOf(String value) {
        return value == null ? null : PaymentStatus.valueOf(value);
    }

    private final JdbcTemplate jdbcTemplate;

    public JdbcCheckoutRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insert(CheckoutSession s) {
        try {
            jdbcTemplate.update(INSERT,
                    s.id(), s.merchantId(), s.status().name(), s.currency(), s.totalAmount(),
                    s.fulfillmentAddressJson(), s.messagesJson(), s.lineSkusJson(),
                    s.canonicalCheckout(), s.merchantAuthorizationJws(), s.expiresAt());
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public Optional<CheckoutSession> findById(String id) {
        return jdbcTemplate.query(SELECT, ROW_MAPPER, id).stream().findFirst();
    }

    @Override
    public boolean cancel(String id) {
        return jdbcTemplate.update(CANCEL, id) == 1;
    }

    @Override
    public int expireReady(OffsetDateTime now, int limit) {
        return jdbcTemplate.update(EXPIRE, now, limit);
    }

    @Override
    public boolean beginComplete(String id, OffsetDateTime now) {
        return jdbcTemplate.update(BEGIN_COMPLETE, id, now) == 1;
    }

    @Override
    public boolean finishComplete(String id, String completionResponseJson) {
        return jdbcTemplate.update(FINISH_COMPLETE, completionResponseJson, id) == 1;
    }

    @Override
    public boolean revertComplete(String id) {
        return jdbcTemplate.update(REVERT_COMPLETE, id) == 1;
    }

    @Override
    public boolean beginPayment(String id, OffsetDateTime requestedAt, String credentialId,
            OffsetDateTime firstRecheckAt) {
        return jdbcTemplate.update(BEGIN_PAYMENT, requestedAt, credentialId, firstRecheckAt, id) == 1;
    }

    @Override
    public List<PaymentRecheck> claimUnknownDue(OffsetDateTime now, OffsetDateTime leaseUntil,
            int maxAttempts, int limit) {
        return jdbcTemplate.query(CLAIM_UNKNOWN_DUE, RECHECK_MAPPER, leaseUntil, now, maxAttempts, limit);
    }

    @Override
    public boolean resolvePayment(String id, PaymentStatus status, String payPaymentId) {
        return jdbcTemplate.update(RESOLVE_PAYMENT, status.name(), payPaymentId, id) == 1;
    }

    @Override
    public boolean scheduleNextRecheck(String id, OffsetDateTime nextAttemptAt) {
        return jdbcTemplate.update(SCHEDULE_NEXT_RECHECK, nextAttemptAt, id) == 1;
    }

    @Override
    public boolean finishPayment(String id, PaymentStatus status, String payPaymentId) {
        return jdbcTemplate.update(FINISH_PAYMENT, status.name(), payPaymentId, id) == 1;
    }

    @Override
    public boolean update(CheckoutSession s, long expectedVersion) {
        int updated = jdbcTemplate.update(UPDATE,
                s.status().name(), s.currency(), s.totalAmount(), s.fulfillmentAddressJson(),
                s.messagesJson(), s.lineSkusJson(), s.canonicalCheckout(), s.merchantAuthorizationJws(),
                s.expiresAt(), s.id(), expectedVersion);
        return updated == 1;
    }
}
