-- UCP Merchant Mock 업무 테이블 4개
-- 근거: DESIGN.md 5.4절, docs/erd/schema.dbml
-- 논리 엔터티 18개를 물리 4개로 합쳤다. 합친 기준은 DESIGN.md 5.3절.
--
-- 상태 컬럼은 PostgreSQL enum 타입이 아니라 varchar + CHECK 로 둔다(ADR 0001).
-- 값 추가가 ALTER TYPE 없이 마이그레이션 한 줄로 끝나고 JdbcTemplate 매핑이 단순하다.
--
-- 금액은 전부 minor unit BIGINT. KRW는 소수 0자리다.
-- 시각은 전부 timestamptz.

CREATE TABLE products (
    id            varchar(36)  PRIMARY KEY,
    external_id   varchar(100) NOT NULL UNIQUE,
    title         varchar(255) NOT NULL,
    image_url     varchar(500),
    base_price    bigint       NOT NULL,
    currency      char(3)      NOT NULL,
    status        varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    variants_json jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_products_status CHECK (status IN ('ACTIVE', 'DISCONTINUED'))
);

COMMENT ON TABLE  products               IS '상품과 옵션. 옵션·재고를 variants_json 으로 흡수했다';
COMMENT ON COLUMN products.external_id   IS 'UCP 응답에 노출되는 상품 코드';
COMMENT ON COLUMN products.base_price    IS 'minor unit. 옵션 추가금은 variants_json 의 priceDelta';
COMMENT ON COLUMN products.variants_json IS '[{sku, optionName, optionValue, priceDelta, stock, status}]';


CREATE TABLE checkout_sessions (
    id                         varchar(36)  PRIMARY KEY,
    buyer_ref                  varchar(100),
    status                     varchar(24)  NOT NULL DEFAULT 'INCOMPLETE',
    currency                   char(3),
    total_amount               bigint,
    fulfillment_address        jsonb,
    messages_json              jsonb,

    canonical_checkout         text,
    checkout_digest            varchar(100),
    merchant_authorization_jws text,
    signing_kid                varchar(100),
    expires_at                 timestamptz,

    completion_key             varchar(255) UNIQUE,
    completion_request_hash    varchar(64),
    completion_response_json   jsonb,

    payment_status             varchar(12),
    pay_payment_id             varchar(100),
    processor_transaction_id   varchar(100),
    payment_error_code         varchar(50),
    payment_attempt_count      int          NOT NULL DEFAULT 0,
    payment_requested_at       timestamptz,

    version                    bigint       NOT NULL DEFAULT 0,
    created_at                 timestamptz  NOT NULL DEFAULT now(),
    updated_at                 timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_checkout_status CHECK (status IN (
        'INCOMPLETE', 'REQUIRES_ESCALATION', 'READY_FOR_COMPLETE',
        'COMPLETE_IN_PROGRESS', 'COMPLETED', 'CANCELED'
    )),
    CONSTRAINT ck_checkout_payment_status CHECK (payment_status IN (
        'REQUESTED', 'SUCCESS', 'FAILED', 'UNKNOWN'
    ))
);

CREATE INDEX ix_checkout_expiry  ON checkout_sessions (status, expires_at);
CREATE INDEX ix_checkout_payment ON checkout_sessions (payment_status, payment_requested_at);

COMMENT ON TABLE  checkout_sessions                            IS '계산서. 체크아웃·서명·멱등·결제 네 관심사를 한 행에 담아 Complete 검증을 SELECT 한 번으로 끝낸다';
COMMENT ON COLUMN checkout_sessions.id                         IS 'Mandate 의 business_checkout_id';
COMMENT ON COLUMN checkout_sessions.buyer_ref                  IS '불투명 참조. Core 가 아직 보내지 않는다';
COMMENT ON COLUMN checkout_sessions.total_amount               IS '빠른 비교용 캐시. 어긋나면 canonical_checkout 이 진실';
COMMENT ON COLUMN checkout_sessions.canonical_checkout         IS 'JCS 정규화 결과 문자열. 검증할 때 재정규화하지 않는다';
COMMENT ON COLUMN checkout_sessions.checkout_digest            IS 'sha256:<base64url-nopad>';
COMMENT ON COLUMN checkout_sessions.merchant_authorization_jws IS 'ES256 Detached JWS. 한 체크아웃에 두 번 서명하지 않는다';
COMMENT ON COLUMN checkout_sessions.signing_kid                IS '키 교체 후에도 옛 체크아웃을 검증하기 위해';
COMMENT ON COLUMN checkout_sessions.expires_at                 IS '서명 시각 + 5분';
COMMENT ON COLUMN checkout_sessions.completion_key             IS 'Core 가 준 Idempotency-Key';
COMMENT ON COLUMN checkout_sessions.completion_request_hash    IS '같은 키에 다른 바디면 409 IDEMPOTENCY_KEY_CONFLICT';
COMMENT ON COLUMN checkout_sessions.completion_response_json   IS '재요청 시 결제하지 않고 그대로 재생';
COMMENT ON COLUMN checkout_sessions.payment_status             IS '갱신은 반드시 조건부 UPDATE. WHERE id=? AND payment_status=''REQUESTED''';
COMMENT ON COLUMN checkout_sessions.payment_attempt_count      IS '조회 시도 횟수. 재청구 횟수가 아니다';


CREATE TABLE orders (
    id                  varchar(36) PRIMARY KEY,
    checkout_session_id varchar(36) NOT NULL UNIQUE
                        REFERENCES checkout_sessions (id),
    status              varchar(12) NOT NULL DEFAULT 'CREATED',
    total_amount        bigint      NOT NULL,
    currency            char(3)     NOT NULL,
    raw_order_json      jsonb       NOT NULL,
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_orders_status CHECK (status IN (
        'CREATED', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELED'
    ))
);

COMMENT ON TABLE  orders                     IS '영수증. 결제 SUCCESS 이후에만 생성한다. UNKNOWN 이면 만들지 않는다';
COMMENT ON COLUMN orders.checkout_session_id IS '중복 주문 최종 방어선. UNIQUE 위반이면 기존 주문을 조회해 반환한다';
COMMENT ON COLUMN orders.raw_order_json      IS '주문 조회 응답과 웹훅 본문의 원본 스냅샷';


CREATE TABLE webhook_deliveries (
    webhook_id      varchar(36) PRIMARY KEY,
    order_id        varchar(36) NOT NULL REFERENCES orders (id),
    event_type      varchar(50) NOT NULL,
    payload_json    jsonb       NOT NULL,
    signature_input text,
    signature       text,
    content_digest  text,
    status          varchar(12) NOT NULL DEFAULT 'PENDING',
    attempt_count   int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    response_status int,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_webhook_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_webhook_event_type CHECK (event_type IN ('order.created', 'order.updated'))
);

CREATE INDEX ix_webhook_pending ON webhook_deliveries (status, next_attempt_at);
CREATE INDEX ix_webhook_order   ON webhook_deliveries (order_id);

COMMENT ON TABLE  webhook_deliveries                 IS '등기우편 발송 대장. order.created / order.updated 발송 큐 겸 이력';
COMMENT ON COLUMN webhook_deliveries.webhook_id      IS 'Core 의 중복 판별 키. 재전송해도 바꾸지 않는다';
COMMENT ON COLUMN webhook_deliveries.payload_json    IS '재전송 시 그대로 다시 보낸다';
COMMENT ON COLUMN webhook_deliveries.signature_input IS '마지막 발송 서명 기록. 발송할 때마다 새로 만든다';
COMMENT ON COLUMN webhook_deliveries.next_attempt_at IS '지수 백오프';
