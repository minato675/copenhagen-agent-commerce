-- 몰 스코프(S15P21A207-393, DESIGN.md 5.4절, ADR 0008)
--
-- 컨테이너 하나·DB 하나가 몰 셋을 서빙한다. 테이블을 몰마다 만들지 않고 컬럼 하나로 가른다.
-- 조회 SQL 마다 WHERE merchant_id = ? 가 붙는 것으로 끝나야 한다.
--
-- 기존 행은 전부 merchant-a 다. 지금까지 몰은 하나였고 그 id 가 merchant-a 였다. DEFAULT 로 backfill 한 뒤
-- DEFAULT 를 지운다 — INSERT 가 몰을 빼먹으면 DB 가 막아야 한다.

ALTER TABLE products
    ADD COLUMN merchant_id varchar(100) NOT NULL DEFAULT 'merchant-a';
ALTER TABLE products
    ALTER COLUMN merchant_id DROP DEFAULT;
COMMENT ON COLUMN products.merchant_id IS
    '몰 스코프(393). merchant.malls[].id·Core services.merchants[].id·경로 세그먼트와 같은 문자열';

-- external_id 는 UCP 응답의 productId 라 몰 사이에서 같다(가격 비교의 열쇠). UNIQUE 는 몰 안에서만 건다.
ALTER TABLE products
    DROP CONSTRAINT products_external_id_key;
ALTER TABLE products
    ADD CONSTRAINT uq_products_merchant_external UNIQUE (merchant_id, external_id);
CREATE INDEX ix_products_merchant ON products (merchant_id);

ALTER TABLE checkout_sessions
    ADD COLUMN merchant_id varchar(100) NOT NULL DEFAULT 'merchant-a';
ALTER TABLE checkout_sessions
    ALTER COLUMN merchant_id DROP DEFAULT;
COMMENT ON COLUMN checkout_sessions.merchant_id IS
    '몰 스코프(393). 조회는 경로의 몰과 이 값을 대조해 다르면 404';

ALTER TABLE orders
    ADD COLUMN merchant_id varchar(100) NOT NULL DEFAULT 'merchant-a';
ALTER TABLE orders
    ALTER COLUMN merchant_id DROP DEFAULT;
COMMENT ON COLUMN orders.merchant_id IS
    '몰 스코프(393). 체크아웃에서 복사. 웹훅 페이로드의 merchant_id 원본';
