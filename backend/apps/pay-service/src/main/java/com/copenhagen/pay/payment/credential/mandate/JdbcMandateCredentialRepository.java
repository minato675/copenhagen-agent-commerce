package com.copenhagen.pay.payment.credential.mandate;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMandateCredentialRepository implements MandateCredentialRepository {

    private static final String INSERT_CHECKOUT_MANDATE_SQL = """
            INSERT INTO checkout_mandates
                (id, authorization_session_id, session_item_id, jti, issuer, audience, checkout_digest,
                 signed_artifact_encrypted, artifact_hash, status, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?)
            """;

    private static final String INSERT_PAYMENT_MANDATE_SQL = """
            INSERT INTO payment_mandates
                (id, authorization_session_id, session_item_id, jti, transaction_id, merchant_id,
                 external_checkout_id, amount_minor, currency, payment_method_ref,
                 signed_artifact_encrypted, artifact_hash, status, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?)
            """;

    private static final String INSERT_CREDENTIAL_SQL = """
            INSERT INTO payment_credential_tokens
                (id, authorization_session_id, session_item_id, checkout_mandate_id, payment_mandate_id,
                 token_hash, token_ciphertext, merchant_id, external_checkout_id,
                 checkout_digest, amount_minor, currency, status, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcMandateCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Issuance issuance) {
        jdbcTemplate.update(
                INSERT_CHECKOUT_MANDATE_SQL,
                issuance.checkoutMandateId(),
                issuance.authorizationSessionId(),
                issuance.sessionItemId(),
                issuance.checkoutMandateJti(),
                issuance.issuer(),
                issuance.audience(),
                issuance.checkoutDigest(),
                issuance.checkoutArtifactEncrypted(),
                issuance.checkoutArtifactHash(),
                toTimestamp(issuance.issuedAt()),
                toTimestamp(issuance.expiresAt()));
        jdbcTemplate.update(
                INSERT_PAYMENT_MANDATE_SQL,
                issuance.paymentMandateId(),
                issuance.authorizationSessionId(),
                issuance.sessionItemId(),
                issuance.paymentMandateJti(),
                issuance.transactionId(),
                issuance.merchantId(),
                issuance.externalCheckoutId(),
                issuance.amountMinor(),
                issuance.currency(),
                issuance.paymentMethodRef(),
                issuance.paymentArtifactEncrypted(),
                issuance.paymentArtifactHash(),
                toTimestamp(issuance.issuedAt()),
                toTimestamp(issuance.expiresAt()));
        jdbcTemplate.update(
                INSERT_CREDENTIAL_SQL,
                issuance.credentialId(),
                issuance.authorizationSessionId(),
                issuance.sessionItemId(),
                issuance.checkoutMandateId(),
                issuance.paymentMandateId(),
                issuance.credentialTokenHash(),
                issuance.credentialTokenCiphertext(),
                issuance.merchantId(),
                issuance.externalCheckoutId(),
                issuance.checkoutDigest(),
                issuance.amountMinor(),
                issuance.currency(),
                toTimestamp(issuance.issuedAt()),
                toTimestamp(issuance.expiresAt()));
    }

    /** pgjdbc는 Instant의 SQL 타입을 추론하지 못해 드라이버에서 바로 던진다. */
    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
