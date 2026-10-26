ALTER TABLE purchase_requests
    ADD COLUMN shipping_address_id BIGINT REFERENCES addresses(id),
    ADD COLUMN workflow_lease_until TIMESTAMPTZ,
    ADD COLUMN workflow_retry_at TIMESTAMPTZ,
    ADD COLUMN failure_code VARCHAR(100),
    ADD COLUMN notification_error VARCHAR(100);

CREATE INDEX idx_purchase_workflow ON purchase_requests (status, workflow_retry_at)
    WHERE request_channel = 'MCP';

CREATE TABLE purchase_resume_commands (
    user_id BIGINT NOT NULL REFERENCES users(id),
    idempotency_key UUID NOT NULL,
    purchase_request_id BIGINT NOT NULL REFERENCES purchase_requests(id),
    address_id UUID NOT NULL,
    cart_version INTEGER NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);
