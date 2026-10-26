-- Credential and payment method safety constraints for the app-facing Pay APIs.

ALTER TABLE payment_pin_credentials
    ADD CONSTRAINT uq_payment_pin_credentials_user
        UNIQUE (user_id);

ALTER TABLE payment_pin_credentials
    ADD CONSTRAINT chk_payment_pin_credentials_status
        CHECK (status IN ('ACTIVE', 'LOCKED', 'REVOKED'));

ALTER TABLE payment_pin_credentials
    ADD CONSTRAINT chk_payment_pin_credentials_failed_attempts_max
        CHECK (failed_attempts BETWEEN 0 AND 5);

ALTER TABLE biometric_credentials
    ADD CONSTRAINT chk_biometric_credentials_status
        CHECK (status IN ('ACTIVE', 'REVOKED'));

CREATE UNIQUE INDEX uq_biometric_credentials_active_user_device
    ON biometric_credentials (user_id, device_id)
    WHERE status = 'ACTIVE';

ALTER TABLE payment_methods
    ADD CONSTRAINT chk_payment_methods_status
        CHECK (status IN ('ACTIVE', 'REVOKED'));

CREATE UNIQUE INDEX uq_payment_methods_provider_token
    ON payment_methods (provider, provider_token_ref);

CREATE UNIQUE INDEX uq_payment_methods_active_default_user
    ON payment_methods (user_id)
    WHERE status = 'ACTIVE'
      AND deleted_at IS NULL
      AND is_default = TRUE;
