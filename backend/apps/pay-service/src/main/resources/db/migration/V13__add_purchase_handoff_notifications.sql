ALTER TABLE pay_notification_outbox DROP CONSTRAINT chk_pay_notification_outbox_event_type;
ALTER TABLE pay_notification_outbox ADD CONSTRAINT chk_pay_notification_outbox_event_type
    CHECK (event_type IN ('APPROVAL_REQUESTED', 'PAYMENT_RESULT', 'PURCHASE_HANDOFF'));

CREATE TABLE pay_notification_resends (
    user_id CHAR(36) NOT NULL,
    idempotency_key UUID NOT NULL,
    purchase_request_id CHAR(36) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);
