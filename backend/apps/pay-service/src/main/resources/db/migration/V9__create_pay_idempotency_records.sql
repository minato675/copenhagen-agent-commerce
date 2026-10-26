CREATE TABLE pay_idempotency_records (
    id CHAR(36) NOT NULL,
    caller_type VARCHAR(20) NOT NULL,
    caller_id VARCHAR(150) NOT NULL,
    service_type VARCHAR(30) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    normalized_path VARCHAR(512) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_status SMALLINT,
    response_content_type VARCHAR(150),
    response_body_encrypted TEXT,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ(6),
    CONSTRAINT pk_pay_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uq_pay_idempotency_scope UNIQUE (
        caller_type,
        caller_id,
        service_type,
        http_method,
        normalized_path,
        idempotency_key
    ),
    CONSTRAINT chk_pay_idempotency_status
        CHECK (status IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT chk_pay_idempotency_response_status
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT chk_pay_idempotency_completion
        CHECK (
            (status = 'PROCESSING' AND response_status IS NULL AND completed_at IS NULL)
            OR (status = 'COMPLETED' AND response_status IS NOT NULL AND completed_at IS NOT NULL)
        )
);

CREATE INDEX idx_pay_idempotency_created_at
    ON pay_idempotency_records (created_at);
