-- Pay MVP hardening rules.
--
-- V2 is kept immutable. This migration makes the approval/payment lifecycle
-- and reconciliation safety rules enforceable at the database boundary.

ALTER TABLE authorization_sessions
    DROP CONSTRAINT chk_authorization_sessions_status;

ALTER TABLE authorization_sessions
    ADD CONSTRAINT chk_authorization_sessions_status
        CHECK (status IN (
            'PAYMENT_METHOD_REQUIRED',
            'PENDING',
            'APPROVED',
            'COMPLETED',
            'REJECTED',
            'EXPIRED',
            'INVALIDATED'
        ));

ALTER TABLE authorization_sessions
    ADD CONSTRAINT chk_authorization_sessions_currency
        CHECK (currency = 'KRW');

ALTER TABLE pay_verified_checkout_evidence
    ADD CONSTRAINT chk_checkout_evidence_currency
        CHECK (currency = 'KRW');

ALTER TABLE payment_mandates
    ADD CONSTRAINT chk_payment_mandates_currency
        CHECK (currency = 'KRW');

ALTER TABLE payment_credential_tokens
    ADD CONSTRAINT chk_payment_credentials_currency
        CHECK (currency = 'KRW');

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_currency
        CHECK (currency = 'KRW');

-- A purchase request is one logical purchase. FAILED payments may be retried
-- with a new approval/credential; a live, successful, or UNKNOWN payment may
-- not be duplicated for the same Merchant and purchase request.
CREATE UNIQUE INDEX uq_payments_live_purchase_request
    ON payments (merchant_account_id, purchase_request_id)
    WHERE status IN ('READY', 'PROCESSING', 'SUCCESS', 'UNKNOWN');

ALTER TABLE payment_reconciliation_jobs
    ADD COLUMN lease_expires_at TIMESTAMPTZ(6);

ALTER TABLE payment_reconciliation_jobs
    DROP CONSTRAINT chk_reconciliation_jobs_status;

ALTER TABLE payment_reconciliation_jobs
    ADD CONSTRAINT chk_reconciliation_jobs_status
        CHECK (status IN (
            'PENDING',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'EXHAUSTED',
            'CANCELLED'
        ));

CREATE INDEX idx_reconciliation_jobs_claim
    ON payment_reconciliation_jobs (status, next_run_at, lease_expires_at);
