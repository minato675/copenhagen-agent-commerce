-- 승인 세션의 단위를 Merchant Checkout 에서 구매 요청으로 올린다.
--
-- 판매처가 N곳인 장바구니를 구매하면 지금은 세션이 N개 생기고 사용자가 PIN/생체인증을
-- N번 했다. 세션을 구매 요청당 1개로 묶고 판매처별 정보를 authorization_session_items 로
-- 분리해, 인증 1회로 credential N장을 발급할 수 있게 한다.
--
-- Merchant -> Pay 결제 계약은 바뀌지 않는다. payment_credential_tokens 가 merchant_id,
-- external_checkout_id, checkout_digest, amount_minor 를 자체 보유하므로 결제 검증은
-- credential 만으로 성립한다.
--
-- V14 주의사항을 그대로 적용한다. V14 는 chk_authorization_sessions_payment_method_state 가
-- 만료 스윕의 PAYMENT_METHOD_REQUIRED -> EXPIRED 전이를 막아 승인 목록/상세 조회가 영구히
-- 500 이 되었던 사건의 수정이다. 이 마이그레이션은 컬럼 제거와 백필을 동반하므로 같은 유형의
-- 회귀 여지가 더 크다. 아래 두 가지로 방어한다.
--   1. V14 가 만든 payment_method_state 제약은 status 와 selected_payment_method_id 만
--      참조하므로 건드리지 않는다. 이 마이그레이션이 그 제약의 적용 범위를 넓히지 않는다.
--   2. status CHECK 에서 COMPLETED 를 제거하지 않는다. 애플리케이션은 더 이상 세션을
--      COMPLETED 로 보내지 않지만(결제 결과는 item 이 추적한다), 과거 행이 남아 있으므로
--      허용 목록에서 빼면 기존 행이 제약을 위반한다.
--
-- 배포 전 진행 중 세션을 드레인한다. 드레인 기준은 PAYMENT_METHOD_REQUIRED / PENDING /
-- APPROVED 세션이 0 이 되는 시점이다. 드레인하지 않으면 아래 STEP 2 에서 같은 구매 요청의
-- 활성 세션이 여러 개 남아 EXPIRED 로 정리된다.

-- =====================================================================
-- STEP 1. 판매처별 정보를 담을 item 테이블
-- =====================================================================

CREATE TABLE authorization_session_items (
    id CHAR(36) NOT NULL,
    authorization_session_id CHAR(36) NOT NULL,
    merchant_account_id CHAR(36) NOT NULL,
    external_checkout_id VARCHAR(150) NOT NULL,
    checkout_digest CHAR(64) NOT NULL,
    display_snapshot JSONB NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_authorization_session_items PRIMARY KEY (id),
    -- 한 승인 세션 안에서 같은 판매처가 두 번 나올 수 없다. Core 가 장바구니를 판매처별로
    -- 그룹핑해 보내므로 중복은 재시도 오작동을 뜻한다.
    CONSTRAINT uq_session_items_merchant UNIQUE (authorization_session_id, merchant_account_id),
    CONSTRAINT uq_session_items_checkout UNIQUE (authorization_session_id, external_checkout_id),
    CONSTRAINT chk_session_items_amount CHECK (amount_minor >= 0),
    CONSTRAINT chk_session_items_currency CHECK (currency = 'KRW'),
    CONSTRAINT chk_session_items_display_snapshot
        CHECK (jsonb_typeof(display_snapshot) = 'object'),
    -- 세션은 APPROVED 에서 종료하고 결제 결과는 item 이 추적한다. 판매처 A 성공 /
    -- 판매처 B 실패를 세션 하나의 상태로 표현할 수 없기 때문이다. 구매 전체 집계는
    -- Core 의 PurchaseWorkflowService.aggregate() 가 담당한다.
    CONSTRAINT chk_session_items_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'INVALIDATED', 'EXPIRED')),
    CONSTRAINT fk_session_items_session
        FOREIGN KEY (authorization_session_id)
        REFERENCES authorization_sessions (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_session_items_merchant
        FOREIGN KEY (merchant_account_id)
        REFERENCES merchant_accounts (id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_session_items_session
    ON authorization_session_items (authorization_session_id);

CREATE INDEX idx_session_items_merchant_account_id
    ON authorization_session_items (merchant_account_id);

CREATE INDEX idx_session_items_checkout
    ON authorization_session_items (external_checkout_id);

-- =====================================================================
-- STEP 2. 같은 구매 요청의 활성 세션 중복 정리
--
-- 새 유니크는 (active_purchase_request_key) 단독이다. 기존에는 판매처가 축에 있어
-- 구매 요청 하나에 활성 세션이 여러 개 존재할 수 있었다. 드레인했다면 대상이 없다.
-- 남아 있다면 가장 먼저 만들어진 하나만 남기고 나머지를 EXPIRED 로 정리한다.
-- 병합하지 않는 이유는 흡수되는 세션의 멱등키와 request_hash 를 보존할 방법이 없기
-- 때문이다. 사용자는 구매를 다시 시작하면 된다.
-- =====================================================================

UPDATE authorization_sessions s
SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP, version = version + 1
WHERE s.status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
  AND EXISTS (
      SELECT 1 FROM authorization_sessions older
      WHERE older.purchase_request_id = s.purchase_request_id
        AND older.status IN ('PAYMENT_METHOD_REQUIRED', 'PENDING', 'APPROVED')
        AND (older.created_at, older.id) < (s.created_at, s.id)
  );

-- =====================================================================
-- STEP 3. 세션 1행 -> item 1행 백필
--
-- 세션 컬럼을 제거하기 전에 수행해야 한다. 순서를 뒤집으면 판매처 정보가 사라진 뒤라
-- item 을 만들 수 없다.
-- =====================================================================

INSERT INTO authorization_session_items (
    id, authorization_session_id, merchant_account_id, external_checkout_id,
    checkout_digest, display_snapshot, amount_minor, currency, status,
    created_at, updated_at, version)
SELECT
    md5(s.id || ':item')::uuid::text,
    s.id,
    s.merchant_account_id,
    s.external_checkout_id,
    s.checkout_digest,
    s.display_snapshot,
    s.amount_minor,
    s.currency,
    CASE s.status
        WHEN 'COMPLETED' THEN 'COMPLETED'
        WHEN 'INVALIDATED' THEN 'INVALIDATED'
        WHEN 'EXPIRED' THEN 'EXPIRED'
        WHEN 'REJECTED' THEN 'EXPIRED'
        ELSE 'PENDING'
    END,
    s.created_at,
    s.updated_at,
    0
FROM authorization_sessions s;

-- =====================================================================
-- STEP 4. 하위 테이블에 session_item_id 연결
--
-- evidence / checkout_mandates / payment_mandates / payment_credential_tokens 는
-- 모두 UNIQUE(authorization_session_id) 로 세션당 1행이 강제되어 있다. 세션이 판매처
-- N곳을 담게 되므로 item 단위로 풀어 준다.
-- =====================================================================

ALTER TABLE pay_verified_checkout_evidence ADD COLUMN session_item_id CHAR(36);
ALTER TABLE checkout_mandates ADD COLUMN session_item_id CHAR(36);
ALTER TABLE payment_mandates ADD COLUMN session_item_id CHAR(36);
ALTER TABLE payment_credential_tokens ADD COLUMN session_item_id CHAR(36);

UPDATE pay_verified_checkout_evidence t
SET session_item_id = i.id
FROM authorization_session_items i
WHERE i.authorization_session_id = t.authorization_session_id;

UPDATE checkout_mandates t
SET session_item_id = i.id
FROM authorization_session_items i
WHERE i.authorization_session_id = t.authorization_session_id;

UPDATE payment_mandates t
SET session_item_id = i.id
FROM authorization_session_items i
WHERE i.authorization_session_id = t.authorization_session_id;

UPDATE payment_credential_tokens t
SET session_item_id = i.id
FROM authorization_session_items i
WHERE i.authorization_session_id = t.authorization_session_id;

ALTER TABLE pay_verified_checkout_evidence ALTER COLUMN session_item_id SET NOT NULL;
ALTER TABLE checkout_mandates ALTER COLUMN session_item_id SET NOT NULL;
ALTER TABLE payment_mandates ALTER COLUMN session_item_id SET NOT NULL;
ALTER TABLE payment_credential_tokens ALTER COLUMN session_item_id SET NOT NULL;

ALTER TABLE pay_verified_checkout_evidence
    ADD CONSTRAINT fk_checkout_evidence_session_item
        FOREIGN KEY (session_item_id)
        REFERENCES authorization_session_items (id)
        ON DELETE RESTRICT;

ALTER TABLE checkout_mandates
    ADD CONSTRAINT fk_checkout_mandates_session_item
        FOREIGN KEY (session_item_id)
        REFERENCES authorization_session_items (id)
        ON DELETE RESTRICT;

ALTER TABLE payment_mandates
    ADD CONSTRAINT fk_payment_mandates_session_item
        FOREIGN KEY (session_item_id)
        REFERENCES authorization_session_items (id)
        ON DELETE RESTRICT;

ALTER TABLE payment_credential_tokens
    ADD CONSTRAINT fk_payment_credentials_session_item
        FOREIGN KEY (session_item_id)
        REFERENCES authorization_session_items (id)
        ON DELETE RESTRICT;

-- 세션당 1행 -> item 당 1행
ALTER TABLE pay_verified_checkout_evidence
    DROP CONSTRAINT uq_checkout_evidence_session;
ALTER TABLE pay_verified_checkout_evidence
    ADD CONSTRAINT uq_checkout_evidence_session_item UNIQUE (session_item_id);

ALTER TABLE checkout_mandates
    DROP CONSTRAINT uq_checkout_mandates_session;
ALTER TABLE checkout_mandates
    ADD CONSTRAINT uq_checkout_mandates_session_item UNIQUE (session_item_id);

ALTER TABLE payment_mandates
    DROP CONSTRAINT uq_payment_mandates_session;
ALTER TABLE payment_mandates
    ADD CONSTRAINT uq_payment_mandates_session_item UNIQUE (session_item_id);

ALTER TABLE payment_credential_tokens
    DROP CONSTRAINT uq_payment_credentials_session;
ALTER TABLE payment_credential_tokens
    ADD CONSTRAINT uq_payment_credentials_session_item UNIQUE (session_item_id);

CREATE INDEX idx_checkout_evidence_session ON pay_verified_checkout_evidence (authorization_session_id);
CREATE INDEX idx_checkout_mandates_session ON checkout_mandates (authorization_session_id);
CREATE INDEX idx_payment_mandates_session ON payment_mandates (authorization_session_id);
CREATE INDEX idx_payment_credentials_session ON payment_credential_tokens (authorization_session_id);

-- =====================================================================
-- STEP 5. 세션에서 판매처 컬럼 제거
-- =====================================================================

ALTER TABLE authorization_sessions ADD COLUMN total_amount_minor BIGINT;

UPDATE authorization_sessions SET total_amount_minor = amount_minor;

ALTER TABLE authorization_sessions ALTER COLUMN total_amount_minor SET NOT NULL;

-- 제거할 컬럼을 참조하는 제약/인덱스를 먼저 내린다.
ALTER TABLE authorization_sessions
    DROP CONSTRAINT uq_authorization_sessions_idempotency;
ALTER TABLE authorization_sessions
    DROP CONSTRAINT uq_authorization_sessions_active_purchase_request;
ALTER TABLE authorization_sessions
    DROP CONSTRAINT fk_authorization_sessions_merchant;
ALTER TABLE authorization_sessions
    DROP CONSTRAINT chk_authorization_sessions_amount;
ALTER TABLE authorization_sessions
    DROP CONSTRAINT chk_authorization_sessions_display_snapshot;

DROP INDEX IF EXISTS idx_authorization_sessions_merchant_account_id;

ALTER TABLE authorization_sessions DROP COLUMN merchant_account_id;
ALTER TABLE authorization_sessions DROP COLUMN external_checkout_id;
ALTER TABLE authorization_sessions DROP COLUMN checkout_digest;
ALTER TABLE authorization_sessions DROP COLUMN amount_minor;
ALTER TABLE authorization_sessions DROP COLUMN display_snapshot;

-- 멱등키에서 판매처 축이 빠진다. Core 가 구매 요청당 키 하나를 보낸다.
ALTER TABLE authorization_sessions
    ADD CONSTRAINT uq_authorization_sessions_idempotency UNIQUE (user_id, idempotency_key);

-- 구매 요청당 활성 승인 1건을 DB 가 보장한다. 이전에는 판매처가 축에 있어 N개가 허용됐다.
ALTER TABLE authorization_sessions
    ADD CONSTRAINT uq_authorization_sessions_active_purchase_request
        UNIQUE (active_purchase_request_key);

ALTER TABLE authorization_sessions
    ADD CONSTRAINT chk_authorization_sessions_total_amount
        CHECK (total_amount_minor >= 0);

COMMENT ON COLUMN authorization_sessions.total_amount_minor IS
    '전 판매처 합계. 판매처별 금액은 authorization_session_items.amount_minor 에 있다.';

COMMENT ON COLUMN authorization_session_items.status IS
    'PENDING -> COMPLETED / INVALIDATED / EXPIRED. 결제 결과를 판매처별로 추적한다. '
    '세션은 APPROVED 에서 종료하며 결제 결과로 상태가 바뀌지 않는다.';
