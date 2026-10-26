-- 주문내역이 상품 이미지를 보여줄 수 있도록 주문서 상품 스냅샷에 이미지 URL 을 남긴다.
--
-- 이미지는 merchant Checkout 응답에 없다. product_url·selected_options 과 같은 경로로
-- 담기 시점의 cart_items.image_url 을 옮겨 담는다(OrderValidationService.toSnapshotItems).
--
-- 기존 행은 NULL 로 둔다. 과거 주문의 이미지는 되살릴 근거가 없다(447 감사 문서와 같은 판단).
ALTER TABLE checkout_snapshot_items ADD COLUMN image_url VARCHAR(2048);

COMMENT ON COLUMN checkout_snapshot_items.image_url IS
    '담기 시점에 Catalog 에서 캡처한 대표 이미지 URL. merchant Checkout 응답에 없어 장바구니에서 옮겨 담는다';
