-- 이슈 S15P21A207-486: 승인 단위가 checkout에서 구매 요청으로 올라간다.
-- 승인 생성 요청 본문과 재시도 시각도 구매 요청 단위가 된다.
--
-- merchant_checkouts.approval_request / retry_after는 남겨둔다. 배포 시점에 이미 CREATING 상태로
-- 진행 중이던 구건이 그 값을 들고 있고, 새 워커는 값이 없으면 checkout 스냅샷에서 본문을 다시
-- 만들므로 복구에 문제가 없다. 컬럼 제거는 구 계약 제거 단계에서 한다.

ALTER TABLE purchase_requests
    ADD COLUMN approval_request JSONB,
    ADD COLUMN approval_retry_at TIMESTAMPTZ;

CREATE INDEX idx_purchase_requests_approval_retry
    ON purchase_requests (approval_retry_at)
    WHERE approval_request IS NOT NULL;

COMMENT ON COLUMN purchase_requests.approval_request IS
    'Pay 승인 세션 생성 요청 본문. 워커가 같은 멱등키와 같은 본문으로 재시도한다';
COMMENT ON COLUMN purchase_requests.approval_retry_at IS
    '승인 생성 재시도 가능 시각. 워커가 집을 때 30초 뒤로 밀어 중복 실행을 막는다';
