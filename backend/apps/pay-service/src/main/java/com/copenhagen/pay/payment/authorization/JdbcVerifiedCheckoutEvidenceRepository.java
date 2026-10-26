package com.copenhagen.pay.payment.authorization;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVerifiedCheckoutEvidenceRepository implements VerifiedCheckoutEvidenceRepository {

    private static final String INSERT_SQL = """
            INSERT INTO pay_verified_checkout_evidence
                (id, authorization_session_id, session_item_id, merchant_id, external_checkout_id,
                 checkout_digest, canonical_checkout_json, merchant_authorization,
                 merchant_key_id, signature_algorithm, signature_verified,
                 amount_minor, currency, verified_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcVerifiedCheckoutEvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insert(VerifiedCheckoutEvidence evidence) {
        int inserted = jdbcTemplate.update(
                INSERT_SQL,
                evidence.id(),
                evidence.authorizationSessionId(),
                evidence.sessionItemId(),
                evidence.merchantId(),
                evidence.externalCheckoutId(),
                evidence.checkoutDigest(),
                evidence.canonicalCheckoutJson(),
                evidence.merchantAuthorization(),
                evidence.merchantKeyId(),
                evidence.signatureAlgorithm(),
                evidence.signatureVerified(),
                evidence.amountMinor(),
                evidence.currency(),
                Timestamp.from(evidence.verifiedAt()),
                Timestamp.from(evidence.expiresAt()));
        return inserted == 1;
    }
}
