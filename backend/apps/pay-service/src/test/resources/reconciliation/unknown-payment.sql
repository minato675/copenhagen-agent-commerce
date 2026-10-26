-- Synthetic, isolated PostgreSQL fixture; no real credentials or card data.
INSERT INTO merchant_accounts (id, merchant_id, display_name, auth_type, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'merchant-test', 'Test Merchant', 'TOKEN', 'ACTIVE');

INSERT INTO payment_methods (id, user_id, provider, provider_token_ref, status)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003',
        'mock', 'synthetic-provider-ref', 'ACTIVE');

INSERT INTO authorization_sessions
    (id, purchase_request_id, user_id,
     total_amount_minor, currency, selected_payment_method_id, status, idempotency_key, request_hash,
     expires_at)
VALUES ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000005',
        '00000000-0000-0000-0000-000000000003',
        87000, 'KRW', '00000000-0000-0000-0000-000000000002',
        'APPROVED', 'approval-key', repeat('b', 64), '2026-09-14T01:00:00Z');

INSERT INTO authorization_session_items
    (id, authorization_session_id, merchant_account_id, external_checkout_id, checkout_digest,
     display_snapshot, amount_minor, currency, status)
VALUES ('00000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-000000000004',
        '00000000-0000-0000-0000-000000000001', 'checkout-test', repeat('a', 64),
        '{}', 87000, 'KRW', 'PENDING');

INSERT INTO checkout_mandates
    (id, authorization_session_id, session_item_id, jti, issuer, audience, checkout_digest, signed_artifact_encrypted,
     artifact_hash, status, issued_at, expires_at)
VALUES ('00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000104',
        'checkout-jti', 'pay-test', 'merchant-test', repeat('a', 64), 'synthetic-encrypted',
        repeat('c', 64), 'ISSUED', '2026-09-14T00:00:00Z', '2026-09-14T01:00:00Z');

INSERT INTO payment_mandates
    (id, authorization_session_id, session_item_id, jti, transaction_id, merchant_id, external_checkout_id, amount_minor,
     currency, payment_method_ref, signed_artifact_encrypted, artifact_hash, status, issued_at, expires_at)
VALUES ('00000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000104',
        'payment-jti', 'mandate-tx', 'merchant-test', 'checkout-test', 87000, 'KRW', 'synthetic-provider-ref',
        'synthetic-encrypted', repeat('d', 64), 'ISSUED', '2026-09-14T00:00:00Z', '2026-09-14T01:00:00Z');

INSERT INTO payment_credential_tokens
    (id, authorization_session_id, session_item_id, checkout_mandate_id, payment_mandate_id, token_hash, token_ciphertext,
     merchant_id, external_checkout_id, checkout_digest, amount_minor, currency, status, issued_at, expires_at,
     consumed_at)
VALUES ('00000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000104',
        '00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000007', repeat('e', 64),
        'synthetic-encrypted', 'merchant-test', 'checkout-test', repeat('a', 64), 87000, 'KRW', 'CONSUMED',
        '2026-09-14T00:00:00Z', '2026-09-14T01:00:00Z', '2026-09-14T00:00:00Z');

INSERT INTO payments
    (id, merchant_account_id, external_checkout_id, purchase_request_id, credential_token_id,
     amount_minor, currency, status, idempotency_key, request_hash)
VALUES ('00000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001',
        'checkout-test', '00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000008',
        87000, 'KRW', 'UNKNOWN', 'payment-key', repeat('f', 64));

INSERT INTO payment_attempts
    (id, payment_id, attempt_no, operation, request_hash, pg_request_id, status, started_at)
VALUES ('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000009',
        1, 'CHARGE', repeat('f', 64), 'request-test', 'UNKNOWN', '2026-09-14T00:00:00Z');

INSERT INTO pg_transactions (id, payment_attempt_id, status, requested_at)
VALUES ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000010',
        'UNKNOWN', '2026-09-14T00:00:00Z');

INSERT INTO payment_reconciliation_jobs (id, payment_attempt_id, reason, next_run_at)
VALUES ('00000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000010',
        'PG_TIMEOUT', '2026-09-14T00:00:00Z');
