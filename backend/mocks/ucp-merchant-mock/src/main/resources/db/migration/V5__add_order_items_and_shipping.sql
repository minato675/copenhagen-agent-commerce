-- 주문 상세 분리와 배송 스냅샷. 물리 테이블 4개 → 5개
-- 근거: DESIGN.md 5.3.1절, ADR 0002, 2026-09-14 ERD 컨설팅 피드백
--
-- 지적은 둘이었다. 주문이 raw_order_json 하나뿐이라 무엇이 몇 개 팔렸는지 SQL 로 알 수 없다는 것과,
-- 주문에 배송지와 연락처가 없다는 것이다.
--
-- 체크아웃 항목은 분리하지 않는다. 서명 대상이라 쪼개면 원본이 둘이 된다(DESIGN.md 5.3 두 번째 기준).
-- 경계는 소유다. 내가 판 기록은 관계로, Core 에 돌려준 계산 결과는 스냅샷으로 둔다.

CREATE TABLE order_items (
    id          varchar(36)  PRIMARY KEY,
    order_id    varchar(36)  NOT NULL REFERENCES orders (id),
    line_no     int          NOT NULL,
    product_id  varchar(36)  NOT NULL REFERENCES products (id),
    sku         varchar(100) NOT NULL,
    title       varchar(255) NOT NULL,
    unit_amount bigint       NOT NULL,
    quantity    int          NOT NULL,
    line_amount bigint       NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_order_items_line   UNIQUE (order_id, line_no),
    CONSTRAINT ck_order_items_qty    CHECK (quantity > 0),
    CONSTRAINT ck_order_items_amount CHECK (line_amount = unit_amount * quantity)
);

CREATE INDEX ix_order_items_order   ON order_items (order_id);
CREATE INDEX ix_order_items_product ON order_items (product_id);

COMMENT ON TABLE  order_items             IS '주문 상세. 주문 한 건에 팔린 상품을 한 줄씩 남긴다';
COMMENT ON COLUMN order_items.product_id  IS '내가 파는 상품이라 관계가 실재한다. 상품별 판매 집계가 이 FK 를 탄다';
COMMENT ON COLUMN order_items.sku         IS 'products.variants_json 안의 값이라 FK 를 걸 대상이 없다';
COMMENT ON COLUMN order_items.title       IS '주문 시점 상품명 사본. 상품명이 바뀌어도 과거 주문은 그대로다';
COMMENT ON COLUMN order_items.unit_amount IS '주문 시점 단가 사본. minor unit';
COMMENT ON COLUMN order_items.line_amount IS 'unit_amount * quantity. 계산으로 나오지만 집계가 직접 SUM 한다';


ALTER TABLE orders
    ADD COLUMN recipient_name  varchar(100),
    ADD COLUMN recipient_phone varchar(40),
    ADD COLUMN postal_code     varchar(20),
    ADD COLUMN address_line1   varchar(255),
    ADD COLUMN address_line2   varchar(255);

COMMENT ON COLUMN orders.recipient_name  IS '주문 시점 배송 정보 사본. 체크아웃을 조인해 읽지 않는다';
COMMENT ON COLUMN orders.recipient_phone IS 'Core 가 아직 보내지 않아 비어 있다. 필드 추가 요청은 DESIGN.md 11절';


-- 테이블 주석에서 비유 표현을 걷어낸다. 2026-09-14 ERD 컨설팅 지적.
-- V2 는 이미 적용됐으므로 고치지 않고 여기서 다시 정의한다.
COMMENT ON TABLE checkout_sessions  IS '체크아웃. 체크아웃·서명·멱등·결제 네 관심사를 한 행에 담아 Complete 검증을 SELECT 한 번으로 끝낸다';
COMMENT ON TABLE orders             IS '주문 마스터. 결제 SUCCESS 이후에만 생성한다. UNKNOWN 이면 만들지 않는다';
COMMENT ON TABLE webhook_deliveries IS '웹훅 발송 큐 겸 이력. order.created / order.updated';
