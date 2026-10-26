-- 주문에 받은 배송비를 남긴다(S15P21A207-427, DESIGN.md 5.4·5.6절, ADR 0009)
--
-- 도서산간 추가금이 포함된 금액이다. 계산으로도 나오지만(total_amount - SUM(order_items.line_amount))
-- 할인이나 세금이 생기는 순간 그 뺄셈이 깨지고, 배송비 규칙이 바뀌면 과거 주문의 배송비가 따라 바뀐다.
-- 돈은 그 시점 값을 고정한다. order_items.line_amount 를 저장한 것과 같은 이유다.
--
-- 도서산간 여부 플래그는 두지 않는다. postal_code 가 이미 있어 규칙으로 다시 판정하면 나오고
-- 지금 읽는 경로가 없다(DESIGN.md 5.3절 "계산으로 나오는 것은 빼라").
--
-- DEFAULT 를 지우지 않는다. merchant_id 와 달리 INSERT 가 빠뜨려도 막을 이유가 없고
-- 0 이 "배송비를 받지 않았다" 로 읽히는 값이다. 기존 행도 그래서 0 이다 — 소급해 계산하지 않는다.

ALTER TABLE orders
    ADD COLUMN shipping_fee_amount bigint NOT NULL DEFAULT 0
        CONSTRAINT ck_orders_shipping_fee CHECK (shipping_fee_amount >= 0);

COMMENT ON COLUMN orders.shipping_fee_amount IS
    '주문 시점에 받은 배송비. 도서산간 추가금 포함. minor unit, 통화는 currency 를 따른다';
