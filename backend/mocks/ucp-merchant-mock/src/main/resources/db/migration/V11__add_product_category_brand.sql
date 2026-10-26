-- 검색 매칭용 분류. 이슈 S15P21A207-470.
-- 시드 원본에 있는데 생성기가 버리던 값이다. UCP 응답에는 싣지 않는다 —
-- 내보내면 Core 와 필드 합의가 필요하고 지금은 매칭에만 쓴다(DESIGN.md 5.7절).
ALTER TABLE products ADD COLUMN category varchar(255);
ALTER TABLE products ADD COLUMN brand    varchar(100);

COMMENT ON COLUMN products.category IS '원본 category_path. 검색 매칭용이며 UCP 응답에 노출하지 않는다';
COMMENT ON COLUMN products.brand    IS '원본 brand. 검색 매칭용이며 UCP 응답에 노출하지 않는다';
