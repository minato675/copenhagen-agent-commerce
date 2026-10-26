-- core ERD v5.1의 제약조건과 인덱스를 만든다.
--
-- 부분 UNIQUE는 CONSTRAINT로 선언할 수 없으므로 CREATE UNIQUE INDEX ... WHERE로 만든다.
-- 인덱스 이름은 ERD의 제약명을 그대로 쓴다.
--
-- uq_oauth_accounts_provider_subject와 uq_oauth_accounts_user_provider는 V2에서 만들어 V4에서
-- 이름만 바꿨으므로 여기서 다시 만들지 않는다.

-- =========================================================================
-- 1. 부분 UNIQUE 인덱스
-- =========================================================================

-- 탈퇴(deleted_at 설정) 후 같은 이메일로 재가입할 수 있도록 활성 사용자만 유일하게 본다.
CREATE UNIQUE INDEX uq_users_email_hash_active
    ON users (email_hash)
    WHERE deleted_at IS NULL;

-- 탈퇴 후 번호 재사용을 허용하기 위해 활성 사용자만 유일하게 본다.
CREATE UNIQUE INDEX uq_users_phone_hash_active
    ON users (phone_hash)
    WHERE deleted_at IS NULL;

-- 사용자당 기기당 활성 refresh token 1개. 회전 시 기존 행을 revoke하고 새 행을 INSERT하므로
-- 폐기된 옛 token_hash가 남아 탈취 재사용 탐지(AUTH_REFRESH_TOKEN_REUSED)가 가능하다.
CREATE UNIQUE INDEX uq_refresh_tokens_user_device
    ON refresh_tokens (user_id, device_id)
    WHERE revoked_at IS NULL;

-- 사용자당 활성 기본 배송지 1건.
CREATE UNIQUE INDEX uq_addresses_user_default
    ON addresses (user_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;

-- 사용자당 Agent 앱당 활성 연동 1건.
CREATE UNIQUE INDEX uq_agent_connections_user_client
    ON agent_connections (user_id, oauth_client_id)
    WHERE revoked_at IS NULL;

-- 사용자당 ACTIVE 장바구니 1건. 파라미터 없는 get_cart가 이를 전제한다.
CREATE UNIQUE INDEX uq_carts_user_active
    ON carts (user_id)
    WHERE status = 'ACTIVE';

-- =========================================================================
-- 2. 복합 UNIQUE 제약조건
-- =========================================================================

ALTER TABLE idempotency_records
    ADD CONSTRAINT uq_idempotency_records_scope_key
        UNIQUE (scope, idempotency_key);

ALTER TABLE cart_items
    ADD CONSTRAINT uq_cart_items_cart_product_variant
        UNIQUE (cart_id, merchant_product_id, merchant_variant_id);

ALTER TABLE merchant_checkouts
    ADD CONSTRAINT uq_merchant_checkouts_merchant_checkout
        UNIQUE (merchant_id, merchant_checkout_id);

-- =========================================================================
-- 3. FK 인덱스
-- =========================================================================
-- PostgreSQL은 FK에 인덱스를 자동 생성하지 않는다. 위 UNIQUE가 선두 컬럼으로 커버하지 못하는
-- FK 컬럼에만 인덱스를 만든다.
--
-- 커버되어 생략하는 것:
--   cart_items.cart_id            → uq_cart_items_cart_product_variant 선두
--   merchant_checkouts.merchant_id → uq_merchant_checkouts_merchant_checkout 선두
--   orders.user_id                → idx_orders_user_id_created_at 선두
--
-- carts.user_id / addresses.user_id / agent_connections.user_id는 선두 컬럼이 같은 부분 UNIQUE가
-- 있으나, 조건에서 벗어난 행(비ACTIVE 장바구니, 삭제된 배송지, 해제된 연동)은 그 인덱스로 찾지 못하므로
-- 별도 인덱스를 둔다.

CREATE INDEX idx_carts_user_id ON carts (user_id);
CREATE INDEX idx_cart_items_merchant_id ON cart_items (merchant_id);
CREATE INDEX idx_agent_connections_user_id ON agent_connections (user_id);
CREATE INDEX idx_agent_connections_oauth_client_id ON agent_connections (oauth_client_id);
CREATE INDEX idx_purchase_requests_user_id ON purchase_requests (user_id);
CREATE INDEX idx_purchase_requests_cart_id ON purchase_requests (cart_id);
CREATE INDEX idx_purchase_requests_agent_connection_id ON purchase_requests (agent_connection_id);
CREATE INDEX idx_merchant_checkouts_purchase_request_id ON merchant_checkouts (purchase_request_id);
CREATE INDEX idx_checkout_snapshots_merchant_checkout_id ON checkout_snapshots (merchant_checkout_id);
CREATE INDEX idx_checkout_snapshot_items_checkout_snapshot_id ON checkout_snapshot_items (checkout_snapshot_id);
CREATE INDEX idx_checkout_fulfillment_option_snapshots_snapshot_id
    ON checkout_fulfillment_option_snapshots (checkout_snapshot_id);
CREATE INDEX idx_orders_checkout_snapshot_id ON orders (checkout_snapshot_id);
CREATE INDEX idx_payment_transactions_checkout_snapshot_id ON payment_transactions (checkout_snapshot_id);
CREATE INDEX idx_payment_transactions_order_id ON payment_transactions (order_id);
CREATE INDEX idx_fraud_assessments_purchase_request_id ON fraud_assessments (purchase_request_id);
CREATE INDEX idx_fraud_assessments_merchant_checkout_id ON fraud_assessments (merchant_checkout_id);
CREATE INDEX idx_fraud_assessments_payment_transaction_id ON fraud_assessments (payment_transaction_id);
CREATE INDEX idx_fraud_assessments_reviewer_id ON fraud_assessments (reviewer_id);
CREATE INDEX idx_agent_refresh_tokens_connection_id ON agent_refresh_tokens (connection_id);
CREATE INDEX idx_addresses_user_id ON addresses (user_id);
CREATE INDEX idx_fcm_device_tokens_user_id ON fcm_device_tokens (user_id);

-- =========================================================================
-- 4. 조회 패턴 인덱스
-- =========================================================================

-- 주문 목록·상세의 커서 페이지네이션. orders.user_id 비정규화의 존재 이유다.
CREATE INDEX idx_orders_user_id_created_at ON orders (user_id, created_at DESC);

-- 만료된 멱등성 기록 정리 배치용.
CREATE INDEX idx_idempotency_records_expires_at ON idempotency_records (expires_at);
