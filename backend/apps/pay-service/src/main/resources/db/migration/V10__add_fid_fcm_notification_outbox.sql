-- FID bindings and the transactional outbox used by Pay's FCM worker.
-- Firebase credentials and message contents are never stored in this schema.

CREATE TABLE pay_notification_installations (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    fid VARCHAR(255) NOT NULL,
    last_seen_at TIMESTAMPTZ(6) NOT NULL,
    invalidated_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_pay_notification_installations PRIMARY KEY (id),
    CONSTRAINT uq_pay_notification_installation_user_fid UNIQUE (user_id, fid),
    CONSTRAINT chk_pay_notification_installation_fid
        CHECK (length(btrim(fid)) BETWEEN 1 AND 255)
);

CREATE UNIQUE INDEX uq_pay_notification_installations_active_fid
    ON pay_notification_installations (fid)
    WHERE invalidated_at IS NULL;

CREATE INDEX idx_pay_notification_installations_active_user
    ON pay_notification_installations (user_id)
    WHERE invalidated_at IS NULL;

CREATE TABLE pay_notification_outbox (
    id CHAR(36) NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    user_id CHAR(36) NOT NULL,
    approval_id CHAR(36),
    payment_id CHAR(36),
    payment_status VARCHAR(20),
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ(6) NOT NULL,
    lease_expires_at TIMESTAMPTZ(6),
    sent_at TIMESTAMPTZ(6),
    last_error VARCHAR(100),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_pay_notification_outbox PRIMARY KEY (id),
    CONSTRAINT uq_pay_notification_outbox_event_id UNIQUE (event_id),
    CONSTRAINT chk_pay_notification_outbox_event_type
        CHECK (event_type IN ('APPROVAL_REQUESTED', 'PAYMENT_RESULT')),
    CONSTRAINT chk_pay_notification_outbox_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SENT', 'FAILED', 'EXHAUSTED')),
    CONSTRAINT chk_pay_notification_outbox_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_pay_notification_outbox_payment_status
        CHECK (payment_status IS NULL OR payment_status IN ('SUCCESS', 'FAILED', 'UNKNOWN'))
);

CREATE INDEX idx_pay_notification_outbox_ready
    ON pay_notification_outbox (status, next_run_at);

CREATE INDEX idx_pay_notification_outbox_user
    ON pay_notification_outbox (user_id, created_at);

CREATE TABLE pay_notification_deliveries (
    id CHAR(36) NOT NULL,
    event_id CHAR(36) NOT NULL,
    fid VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ(6) NOT NULL,
    lease_expires_at TIMESTAMPTZ(6),
    sent_at TIMESTAMPTZ(6),
    last_error VARCHAR(100),
    CONSTRAINT pk_pay_notification_deliveries PRIMARY KEY (id),
    CONSTRAINT uq_pay_notification_delivery_event_fid UNIQUE (event_id, fid),
    CONSTRAINT fk_pay_notification_delivery_event
        FOREIGN KEY (event_id)
        REFERENCES pay_notification_outbox (event_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_pay_notification_delivery_status
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'EXHAUSTED')),
    CONSTRAINT chk_pay_notification_delivery_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_pay_notification_deliveries_ready
    ON pay_notification_deliveries (event_id, status, next_run_at);
