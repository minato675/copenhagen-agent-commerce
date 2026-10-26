-- 이슈 410: 승인 세션 생성·폴링·Merchant Complete Checkout 호출.
-- merchant_checkouts가 Pay 승인 세션을 추적하도록 컬럼과 상태를 추가한다.

ALTER TABLE merchant_checkouts
    ADD COLUMN payment_approval_id VARCHAR(36),
    ADD COLUMN payment_approval_expires_at TIMESTAMPTZ;

ALTER TABLE merchant_checkouts
    DROP CONSTRAINT merchant_checkouts_status_check;

ALTER TABLE merchant_checkouts
    ADD CONSTRAINT merchant_checkouts_status_check
        CHECK (status IN ('CREATING', 'AWAITING_APPROVAL', 'COMPLETING', 'COMPLETED',
                          'CANCELLED', 'EXPIRED', 'FAILED'));

COMMENT ON COLUMN merchant_checkouts.payment_approval_id IS
    'Pay 승인 세션(authorization_sessions.id) 식별자. AWAITING_APPROVAL부터 채워진다';
COMMENT ON COLUMN merchant_checkouts.payment_approval_expires_at IS
    'Pay 승인 세션 만료 시각. 폴링 워커가 이 시각을 지나면 EXPIRED로 정리한다';
