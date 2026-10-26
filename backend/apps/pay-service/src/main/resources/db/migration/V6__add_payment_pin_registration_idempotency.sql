ALTER TABLE payment_pin_credentials
    ADD COLUMN registration_idempotency_key VARCHAR(100),
    ADD COLUMN registration_request_hash CHAR(64);
