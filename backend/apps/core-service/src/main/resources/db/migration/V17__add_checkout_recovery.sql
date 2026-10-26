-- 재기동 뒤에도 승인 생성 요청을 같은 본문으로 재시도한다.
ALTER TABLE merchant_checkouts
    ADD COLUMN approval_request JSONB,
    ADD COLUMN retry_after TIMESTAMPTZ,
    ADD COLUMN completion_lease_until TIMESTAMPTZ;

CREATE INDEX idx_merchant_checkouts_recovery ON merchant_checkouts (status, retry_after);

-- Pay 거래 생성 이전의 확정 실패에는 Pay 거래 ID가 없다.
ALTER TABLE payment_transactions ALTER COLUMN pay_transaction_id DROP NOT NULL;
ALTER TABLE payment_transactions ADD CONSTRAINT ck_payment_transaction_identity
    CHECK (pay_transaction_id IS NOT NULL OR status = 'FAILED');

-- Merchant가 보낸 갱신 시각으로 순서가 뒤집힌 배송 이벤트를 판별한다.
ALTER TABLE orders ADD COLUMN merchant_updated_at TIMESTAMPTZ;

CREATE TABLE order_webhook_receipts (
    merchant_id BIGINT NOT NULL REFERENCES merchants(id),
    webhook_id VARCHAR(100) NOT NULL,
    payload_digest VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (merchant_id, webhook_id)
);
