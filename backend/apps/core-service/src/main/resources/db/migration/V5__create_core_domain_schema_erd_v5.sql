-- core ERD v5.1의 신규 17개 테이블을 생성한다.
--
-- 생성 순서는 FK 의존 순서다. 이 순서면 forward reference가 없다.
--   merchants → oauth_clients → idempotency_records → carts → cart_items → agent_connections →
--   purchase_requests → merchant_checkouts → checkout_snapshots → checkout_snapshot_items →
--   checkout_fulfillment_option_snapshots → orders → payment_transactions → fraud_assessments →
--   agent_refresh_tokens → addresses → fcm_device_tokens
--
-- 단일 컬럼 UNIQUE는 여기서 바로 선언하고, 복합 UNIQUE와 부분 UNIQUE 인덱스는 V6에서 만든다.
-- 이 단계는 스키마만 만든다. 도메인 레코드·Repository는 각 기능 구현 단계에서 추가한다.

-- =========================================================================
-- 1. merchants — 판매자
-- =========================================================================
CREATE TABLE merchants (
    id BIGSERIAL PRIMARY KEY,
    merchant_name VARCHAR(150) NOT NULL,
    ucp_profile_url VARCHAR(2048) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    business_profile JSONB NOT NULL,
    active_capabilities JSONB NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
    profile_fetched_at TIMESTAMPTZ,
    profile_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE merchants IS '판매자';
COMMENT ON COLUMN merchants.ucp_profile_url IS 'UCP Business Profile 주소';
COMMENT ON COLUMN merchants.base_url IS '판매자 API 기본 주소';
COMMENT ON COLUMN merchants.business_profile IS '판매자 프로필 원문';
COMMENT ON COLUMN merchants.active_capabilities IS '협상 완료된 UCP Capability';

-- =========================================================================
-- 2. oauth_clients — Agent 앱 등록부
-- =========================================================================
CREATE TABLE oauth_clients (
    id BIGSERIAL PRIMARY KEY,
    client_id VARCHAR(255) NOT NULL UNIQUE,
    client_secret_hash VARCHAR(255),
    client_name VARCHAR(150) NOT NULL,
    client_type VARCHAR(20) NOT NULL
        CHECK (client_type IN ('CONFIDENTIAL', 'PUBLIC')),
    redirect_uris JSONB NOT NULL,
    allowed_scopes JSONB NOT NULL,
    logo_url VARCHAR(2048),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE oauth_clients IS 'Agent 앱 등록부';
COMMENT ON COLUMN oauth_clients.client_id IS 'OAuth client_id 쿼리 파라미터 값';
COMMENT ON COLUMN oauth_clients.client_secret_hash IS 'CONFIDENTIAL 클라이언트만 보유. PUBLIC은 NULL(PKCE 사용)';
COMMENT ON COLUMN oauth_clients.redirect_uris IS '허용 리다이렉트 URI. 완전 일치로만 검증(부분 일치·와일드카드 금지)';
COMMENT ON COLUMN oauth_clients.allowed_scopes IS '이 앱이 요청할 수 있는 최대 권한';

-- =========================================================================
-- 3. idempotency_records — 멱등성 기록
-- =========================================================================
CREATE TABLE idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    scope VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    processing_status VARCHAR(24) NOT NULL
        CHECK (processing_status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(255) NOT NULL,
    response_status SMALLINT NOT NULL
        CHECK (response_status BETWEEN 100 AND 599),
    response_payload JSONB NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
);

COMMENT ON TABLE idempotency_records IS '멱등성 기록';
COMMENT ON COLUMN idempotency_records.scope IS '멱등성 적용 API 또는 업무 범위';
COMMENT ON COLUMN idempotency_records.request_hash IS '동일 키의 요청 본문 일치 검증용 해시';
COMMENT ON COLUMN idempotency_records.response_payload IS '재요청에 반환할 응답 내용';

-- =========================================================================
-- 4. carts — 장바구니
-- =========================================================================
CREATE TABLE carts (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'DISCARDED')),
    version INTEGER NOT NULL DEFAULT 1
        CHECK (version > 0),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,

    CONSTRAINT fk_carts_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE carts IS '장바구니';
COMMENT ON COLUMN carts.version IS '낙관적 잠금 또는 스냅샷 버전';

-- =========================================================================
-- 5. cart_items — 장바구니 항목
-- =========================================================================
CREATE TABLE cart_items (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    cart_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    merchant_product_id VARCHAR(255) NOT NULL,
    merchant_variant_id VARCHAR(255) NOT NULL,
    product_name VARCHAR(500) NOT NULL,
    product_url VARCHAR(2048) NOT NULL,
    image_url VARCHAR(2048),
    selected_options JSONB NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1
        CHECK (quantity > 0),
    unit_price_amount BIGINT NOT NULL DEFAULT 0
        CHECK (unit_price_amount >= 0),
    currency CHAR(3) NOT NULL,
    price_captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    added_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,

    CONSTRAINT fk_cart_items_cart
        FOREIGN KEY (cart_id) REFERENCES carts(id),
    CONSTRAINT fk_cart_items_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

COMMENT ON TABLE cart_items IS '장바구니 항목';
COMMENT ON COLUMN cart_items.public_id IS 'MCP 툴에 노출되는 cartItemId';
COMMENT ON COLUMN cart_items.unit_price_amount IS '표시용 캐시 단가. authoritative한 금액은 Checkout 시점에 확정된다';
COMMENT ON COLUMN cart_items.price_captured_at IS '단가를 Merchant Catalog에서 캐시한 시각';

-- =========================================================================
-- 6. agent_connections — Agent 연동
-- =========================================================================
CREATE TABLE agent_connections (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    oauth_client_id BIGINT NOT NULL,
    granted_scopes JSONB NOT NULL,
    connected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_agent_connections_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_agent_connections_oauth_client
        FOREIGN KEY (oauth_client_id) REFERENCES oauth_clients(id)
);

COMMENT ON TABLE agent_connections IS 'Agent 연동';
COMMENT ON COLUMN agent_connections.public_id IS 'API {connectionId}로 노출되는 식별자';
COMMENT ON COLUMN agent_connections.granted_scopes IS '사용자가 동의 화면에서 허용한 범위';
COMMENT ON COLUMN agent_connections.revoked_at IS '연동 해제 시각. NULL이면 활성';

-- =========================================================================
-- 7. purchase_requests — 구매 요청
-- =========================================================================
CREATE TABLE purchase_requests (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    cart_id BIGINT NOT NULL,
    agent_connection_id BIGINT,
    request_channel VARCHAR(20) NOT NULL
        CHECK (request_channel IN ('MCP', 'APP', 'API')),
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('CREATING_CHECKOUTS', 'AWAITING_APPROVAL', 'APPROVED', 'COMPLETING',
                          'COMPLETED', 'PARTIALLY_COMPLETED', 'CANCELLED', 'EXPIRED', 'REJECTED', 'FAILED')),
    policy_version VARCHAR(64) NOT NULL,
    pay_approval_id VARCHAR(255) UNIQUE,
    approval_deadline TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,

    CONSTRAINT fk_purchase_requests_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_purchase_requests_cart
        FOREIGN KEY (cart_id) REFERENCES carts(id),
    CONSTRAINT fk_purchase_requests_agent_connection
        FOREIGN KEY (agent_connection_id) REFERENCES agent_connections(id)
);

COMMENT ON TABLE purchase_requests IS '구매 요청';
COMMENT ON COLUMN purchase_requests.agent_connection_id IS 'request_channel=MCP로 유입된 요청이 거친 Agent 연동';
COMMENT ON COLUMN purchase_requests.policy_version IS '적용한 서버 정책 버전';
COMMENT ON COLUMN purchase_requests.pay_approval_id IS
    'get_payment_request_status의 approvalId 역매핑용. Pay에 승인 세션을 요청한 시점에 채워진다';

-- =========================================================================
-- 8. merchant_checkouts — 판매자 주문서
-- =========================================================================
CREATE TABLE merchant_checkouts (
    id BIGSERIAL PRIMARY KEY,
    purchase_request_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    merchant_checkout_id VARCHAR(255) NOT NULL,
    checkout_digest CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('CREATING', 'COMPLETING', 'COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED')),
    failure_code VARCHAR(100),
    checkout_created_at TIMESTAMPTZ,
    checkout_expires_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,

    CONSTRAINT fk_merchant_checkouts_purchase_request
        FOREIGN KEY (purchase_request_id) REFERENCES purchase_requests(id),
    CONSTRAINT fk_merchant_checkouts_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

COMMENT ON TABLE merchant_checkouts IS '판매자 주문서';
COMMENT ON COLUMN merchant_checkouts.merchant_checkout_id IS '판매자가 발급한 주문서 ID';
COMMENT ON COLUMN merchant_checkouts.checkout_digest IS '승인 대상 주문서 원문 검증용 해시';

-- =========================================================================
-- 9. checkout_snapshots — 주문서 스냅샷
-- =========================================================================
CREATE TABLE checkout_snapshots (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    merchant_checkout_id BIGINT NOT NULL,
    checkout_digest CHAR(64) NOT NULL,
    merchant_checkout_payload JSONB NOT NULL,
    subtotal_amount BIGINT NOT NULL DEFAULT 0
        CHECK (subtotal_amount >= 0),
    discount_amount BIGINT NOT NULL DEFAULT 0
        CHECK (discount_amount >= 0),
    tax_amount BIGINT NOT NULL DEFAULT 0
        CHECK (tax_amount >= 0),
    shipping_fee_amount BIGINT NOT NULL DEFAULT 0
        CHECK (shipping_fee_amount >= 0),
    total_amount BIGINT NOT NULL DEFAULT 0
        CHECK (total_amount >= 0),
    currency CHAR(3) NOT NULL,
    recipient_name_encrypted BYTEA,
    recipient_phone_encrypted BYTEA,
    postal_code_encrypted BYTEA,
    address_line1_encrypted BYTEA,
    address_line2_encrypted BYTEA,
    delivery_request_encrypted BYTEA,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_checkout_snapshots_merchant_checkout
        FOREIGN KEY (merchant_checkout_id) REFERENCES merchant_checkouts(id)
);

COMMENT ON TABLE checkout_snapshots IS '주문서 스냅샷';
COMMENT ON COLUMN checkout_snapshots.public_id IS 'Pay에 넘기는 snapshotId. 내부 BIGINT는 노출하지 않는다';
COMMENT ON COLUMN checkout_snapshots.merchant_checkout_payload IS '캡처 시점의 판매자 주문서 전체 내용';

-- =========================================================================
-- 10. checkout_snapshot_items — 주문서 상품 스냅샷
-- =========================================================================
CREATE TABLE checkout_snapshot_items (
    id BIGSERIAL PRIMARY KEY,
    checkout_snapshot_id BIGINT NOT NULL,
    merchant_product_id VARCHAR(255) NOT NULL,
    merchant_variant_id VARCHAR(255) NOT NULL,
    product_name VARCHAR(500) NOT NULL,
    product_url VARCHAR(2048) NOT NULL,
    selected_options JSONB NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1
        CHECK (quantity > 0),
    unit_price_amount BIGINT NOT NULL DEFAULT 0
        CHECK (unit_price_amount >= 0),
    discount_amount BIGINT NOT NULL DEFAULT 0
        CHECK (discount_amount >= 0),
    line_total_amount BIGINT NOT NULL DEFAULT 0
        CHECK (line_total_amount >= 0),
    currency CHAR(3) NOT NULL,

    CONSTRAINT fk_checkout_snapshot_items_checkout_snapshot
        FOREIGN KEY (checkout_snapshot_id) REFERENCES checkout_snapshots(id)
);

COMMENT ON TABLE checkout_snapshot_items IS '주문서 상품 스냅샷';
COMMENT ON COLUMN checkout_snapshot_items.product_name IS '주문 당시 상품명';

-- =========================================================================
-- 11. checkout_fulfillment_option_snapshots — 주문서 배송 스냅샷
-- =========================================================================
CREATE TABLE checkout_fulfillment_option_snapshots (
    id BIGSERIAL PRIMARY KEY,
    checkout_snapshot_id BIGINT NOT NULL,
    fulfillment_group_key VARCHAR(255) NOT NULL,
    shipping_option_id VARCHAR(255) NOT NULL,
    shipping_option_name VARCHAR(255) NOT NULL,
    delivery_method VARCHAR(30) NOT NULL
        CHECK (delivery_method IN ('STANDARD', 'EXPRESS', 'PICKUP')),
    shipping_fee_amount BIGINT NOT NULL DEFAULT 0
        CHECK (shipping_fee_amount >= 0),
    estimated_delivery_at TIMESTAMPTZ,

    CONSTRAINT fk_checkout_fulfillment_option_snapshots_checkout_snapshot
        FOREIGN KEY (checkout_snapshot_id) REFERENCES checkout_snapshots(id)
);

COMMENT ON TABLE checkout_fulfillment_option_snapshots IS '주문서 배송 스냅샷';
COMMENT ON COLUMN checkout_fulfillment_option_snapshots.fulfillment_group_key IS
    'Merchant Checkout에서 같은 출고·배송 조건으로 묶은 상품 그룹의 식별자';

-- =========================================================================
-- 12. orders — 주문
-- =========================================================================
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    checkout_snapshot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    merchant_order_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('CREATED', 'CONFIRMED', 'FULFILLING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'FAILED')),
    merchant_receipt_url VARCHAR(2048),
    failure_code VARCHAR(100),
    ordered_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,

    CONSTRAINT fk_orders_checkout_snapshot
        FOREIGN KEY (checkout_snapshot_id) REFERENCES checkout_snapshots(id),
    CONSTRAINT fk_orders_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE orders IS '주문';
-- user_id는 의도적 비정규화다. checkout_snapshots → merchant_checkouts → purchase_requests.user_id를
-- 매번 조인하지 않고 주문 목록·상세를 커서 페이지네이션하기 위한 컬럼이며, 항상 그 값과 일치해야 한다.
COMMENT ON COLUMN orders.user_id IS
    '주문 목록·상세 커서 페이지네이션 성능을 위한 비정규화 컬럼. purchase_requests.user_id와 항상 일치한다';
COMMENT ON COLUMN orders.merchant_receipt_url IS '판매자 영수증 또는 주문 조회 링크. 주문 생성 시점엔 NULL, 확정 후 채워진다';

-- =========================================================================
-- 13. payment_transactions — 결제 거래
-- =========================================================================
CREATE TABLE payment_transactions (
    id BIGSERIAL PRIMARY KEY,
    checkout_snapshot_id BIGINT NOT NULL,
    order_id BIGINT,
    authorization_session_id VARCHAR(255) NOT NULL,
    pay_transaction_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'PAYMENT_PENDING', 'PAID',
                          'FAILED', 'CANCELLED', 'EXPIRED')),
    payment_amount BIGINT NOT NULL DEFAULT 0
        CHECK (payment_amount >= 0),
    currency CHAR(3) NOT NULL,
    failure_code VARCHAR(100),
    failure_message VARCHAR(500),
    approved_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,

    CONSTRAINT fk_payment_transactions_checkout_snapshot
        FOREIGN KEY (checkout_snapshot_id) REFERENCES checkout_snapshots(id),
    CONSTRAINT fk_payment_transactions_order
        FOREIGN KEY (order_id) REFERENCES orders(id)
);

COMMENT ON TABLE payment_transactions IS '결제 거래';
COMMENT ON COLUMN payment_transactions.authorization_session_id IS 'Pay가 발급한 사용자 승인 세션 ID';
COMMENT ON COLUMN payment_transactions.pay_transaction_id IS 'Pay가 발급한 거래 식별자';
COMMENT ON COLUMN payment_transactions.order_id IS '실제 Merchant 주문 생성 후 연결되는 주문 식별자';

-- =========================================================================
-- 14. fraud_assessments — 이상거래 평가
-- =========================================================================
CREATE TABLE fraud_assessments (
    id BIGSERIAL PRIMARY KEY,
    purchase_request_id BIGINT NOT NULL,
    merchant_checkout_id BIGINT,
    payment_transaction_id BIGINT,
    assessment_stage VARCHAR(32) NOT NULL
        CHECK (assessment_stage IN ('PRE_APPROVAL', 'POST_APPROVAL', 'PRE_PAYMENT')),
    decision VARCHAR(24) NOT NULL
        CHECK (decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    risk_level VARCHAR(16) NOT NULL
        CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')),
    behavior_score NUMERIC(5, 2)
        CHECK (behavior_score BETWEEN 0 AND 100),
    recommendation VARCHAR(32)
        CHECK (recommendation IN ('NO_ADDITIONAL_REVIEW', 'REVIEW_ORDER', 'VERIFY_USER_INTENT', 'REQUIRE_AUTH')),
    evaluation_status VARCHAR(24) NOT NULL
        CHECK (evaluation_status IN ('SUCCESS', 'INCOMPLETE', 'FAILED', 'CANCELLED')),
    evaluation_id VARCHAR(255) UNIQUE,
    model_name VARCHAR(100),
    model_version VARCHAR(100),
    rule_hits JSONB,
    feature_summary JSONB,
    result_payload JSONB NOT NULL,
    evaluation_snapshot_refs JSONB NOT NULL,
    request_sha256 CHAR(64),
    reviewer_id BIGINT,
    review_note TEXT,
    reviewed_at TIMESTAMPTZ,
    assessed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fraud_assessments_purchase_request
        FOREIGN KEY (purchase_request_id) REFERENCES purchase_requests(id),
    CONSTRAINT fk_fraud_assessments_merchant_checkout
        FOREIGN KEY (merchant_checkout_id) REFERENCES merchant_checkouts(id),
    CONSTRAINT fk_fraud_assessments_payment_transaction
        FOREIGN KEY (payment_transaction_id) REFERENCES payment_transactions(id),
    CONSTRAINT fk_fraud_assessments_reviewer
        FOREIGN KEY (reviewer_id) REFERENCES users(id)
);

COMMENT ON TABLE fraud_assessments IS '이상거래 평가';
COMMENT ON COLUMN fraud_assessments.behavior_score IS 'AI가 산출한 행동 이탈 점수. 사기 확률이 아니다';
COMMENT ON COLUMN fraud_assessments.evaluation_id IS 'AI가 발급한 평가 실행 식별자';
COMMENT ON COLUMN fraud_assessments.result_payload IS 'AI 결과 원문 보관';
COMMENT ON COLUMN fraud_assessments.evaluation_snapshot_refs IS '평가에 사용한 주문서 스냅샷 고정(ID·digest 목록)';
COMMENT ON COLUMN fraud_assessments.reviewer_id IS '수동 검토를 수행한 사용자 식별자';

-- =========================================================================
-- 15. agent_refresh_tokens — Agent 위임 토큰
-- =========================================================================
CREATE TABLE agent_refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    connection_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    scopes JSONB NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_agent_refresh_tokens_connection
        FOREIGN KEY (connection_id) REFERENCES agent_connections(id)
);

COMMENT ON TABLE agent_refresh_tokens IS 'Agent 위임 토큰';
COMMENT ON COLUMN agent_refresh_tokens.token_hash IS '원문 대신 저장하는 해시';
COMMENT ON COLUMN agent_refresh_tokens.scopes IS 'agent_connections.granted_scopes의 부분집합';
COMMENT ON COLUMN agent_refresh_tokens.revoked_at IS '폐기 시각. NULL이면 사용 가능';

-- =========================================================================
-- 16. addresses — 배송지
-- =========================================================================
CREATE TABLE addresses (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    recipient_name_encrypted BYTEA NOT NULL,
    recipient_phone_encrypted BYTEA NOT NULL,
    postal_code_encrypted BYTEA NOT NULL,
    address_line1_encrypted BYTEA NOT NULL,
    address_line2_encrypted BYTEA,
    delivery_request_encrypted BYTEA,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_addresses_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE addresses IS '배송지';
COMMENT ON COLUMN addresses.public_id IS 'API {addressId}로 노출되는 식별자';
COMMENT ON COLUMN addresses.postal_code_encrypted IS '우편번호. 도서산간 배송비 판정용이며 등록 API 필수 입력';
COMMENT ON COLUMN addresses.delivery_request_encrypted IS '배송 메모 기본값. 주문 시 덮어쓸 수 있다';
COMMENT ON COLUMN addresses.deleted_at IS 'Soft Delete 처리 시각. NULL이면 사용 가능';

-- =========================================================================
-- 17. fcm_device_tokens — FCM 디바이스 토큰
-- =========================================================================
CREATE TABLE fcm_device_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(255) NOT NULL UNIQUE,
    fcm_token TEXT NOT NULL UNIQUE,
    platform VARCHAR(10) NOT NULL
        CHECK (platform IN ('ANDROID', 'IOS')),
    last_seen_at TIMESTAMPTZ NOT NULL,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fcm_device_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE fcm_device_tokens IS 'FCM 디바이스 토큰';
COMMENT ON COLUMN fcm_device_tokens.device_id IS '토큰 갱신 시 같은 앱 설치를 식별하는 값';
COMMENT ON COLUMN fcm_device_tokens.fcm_token IS '해당 기기로 푸시 알림을 발송할 때 사용하는 수신 주소';
COMMENT ON COLUMN fcm_device_tokens.invalidated_at IS
    'FCM이 토큰 무효를 반환하거나 사용자가 알림 수신을 해제한 시각. NULL이면 사용 가능';
