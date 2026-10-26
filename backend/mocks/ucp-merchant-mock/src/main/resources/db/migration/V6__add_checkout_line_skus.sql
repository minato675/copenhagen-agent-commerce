-- 체크아웃이 고른 옵션(SKU)을 남긴다. 주문 상세를 만들기 위한 것이다.
-- 근거: DESIGN.md 5.4절 checkout_sessions
--
-- Core 가 보낸 option_value_ids 를 CheckoutEvaluator 가 옵션을 찾는 데 쓰고 버렸다.
-- 남는 것은 그 옵션의 priceDelta 가 더해진 단가뿐이라 Complete 시점에 order_items.sku 를 채울 값이 없었다.
-- priceDelta 가 0 인 옵션이 대부분이라 단가로 역추적도 되지 않는다.
--
-- canonical_checkout 에 넣지 않는다. 그쪽은 서명 대상이고 Core 응답 모양이라 바꾸려면 합의가 필요하다.
-- 이 컬럼은 서명과 무관해서 지금 넣을 수 있다.
--
-- 형태는 {"line-1": "product-101-260"} 이다. canonical_checkout 의 line item id 를 키로 잡는다.
-- 위치가 아니라 id 로 거는 이유는 재계산으로 줄 순서가 바뀌어도 어긋나지 않게 하기 위해서다.
--
-- NULL 을 허용하는 것은 이 마이그레이션 이전 행 때문이다.
-- ready_for_complete 인 체크아웃은 모든 줄에 SKU 가 있다. 옵션을 못 고르면 CHECKOUT_INCOMPLETE 가 붙어
-- 그 상태로 올라가지 못한다. 주문 생성은 이 불변을 전제한다.

ALTER TABLE checkout_sessions
    ADD COLUMN line_skus_json jsonb;

COMMENT ON COLUMN checkout_sessions.line_skus_json
    IS 'line item id -> 선택된 SKU. canonical_checkout 에 자리가 없어 옆에 둔다. 주문 상세가 이걸 읽는다';
