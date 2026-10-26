-- 이슈 S15P21A207-417(순서 34): MCP 내부 업무 API.
--
-- 1) 구매 인계는 배송지 선택 전 단계의 구매 요청을 만든다. 앱 경로(순서 27)는 배송지를 이미 고른 뒤
--    CREATING_CHECKOUTS로 시작하지만, MCP 인계는 배송지가 없는 상태로 적재됐다가 앱이 이어받는다.
-- 2) 내부 API 쓰기 4종(cart 3 + purchase 1)의 Idempotency-Key 저장소.
--
-- V14는 미병합 브랜치 feature/S15P21A207-416-oauth-issuer-identification이 선점하고 있어 V15를 쓴다.

ALTER TABLE purchase_requests
    ADD COLUMN cart_version INTEGER,
    ADD COLUMN expires_at TIMESTAMPTZ;

COMMENT ON COLUMN purchase_requests.cart_version IS
    'MCP 인계 시점의 carts.version. 앱이 이어받을 때 같은 장바구니인지 대조한다';
COMMENT ON COLUMN purchase_requests.expires_at IS
    'PENDING_ADDRESS 인계 링크 만료 시각. MCP는 생성 시각 +31분을 넘으면 응답을 거부한다';

-- 인라인 CHECK라 Postgres 자동 명명(purchase_requests_status_check)을 따른다.
-- V12가 merchant_checkouts_status_check에 쓴 방식과 같다.
ALTER TABLE purchase_requests
    DROP CONSTRAINT purchase_requests_status_check;

ALTER TABLE purchase_requests
    ADD CONSTRAINT purchase_requests_status_check
        CHECK (status IN ('PENDING_ADDRESS', 'CREATING_CHECKOUTS', 'AWAITING_APPROVAL', 'APPROVED',
                          'COMPLETING', 'COMPLETED', 'PARTIALLY_COMPLETED', 'CANCELLED',
                          'EXPIRED', 'REJECTED', 'FAILED'));

CREATE TABLE internal_idempotency_keys (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INTEGER NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_internal_idempotency_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_internal_idempotency_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE internal_idempotency_keys IS 'MCP 내부 API 쓰기 요청의 멱등키(보존 24시간)';
COMMENT ON COLUMN internal_idempotency_keys.request_hash IS
    '같은 키로 다른 요청이 오는 것을 막기 위한 method+path+본문 SHA-256';
COMMENT ON COLUMN internal_idempotency_keys.response_body IS '재전송 시 그대로 돌려줄 최초 성공 응답';
