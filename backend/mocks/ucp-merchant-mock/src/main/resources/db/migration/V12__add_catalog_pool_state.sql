-- 카탈로그 풀 적재 상태(DESIGN.md 5.7절 2026-09-21 개정, ADR 0011).
-- 풀 sha256·몰 목록·limit 이 같으면 기동 때 적재를 건너뛴다. 27만 행을 기동마다 다시 쓰지 않기 위해서다.
-- 한 행뿐이라 id 를 1 로 고정한다.
CREATE TABLE catalog_pool_state (
    id            smallint     PRIMARY KEY,
    pool_sha256   varchar(64)  NOT NULL,
    malls         varchar(255) NOT NULL,
    pool_limit    integer      NOT NULL,
    product_count integer      NOT NULL,
    loaded_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_catalog_pool_state_single CHECK (id = 1)
);

COMMENT ON TABLE  catalog_pool_state               IS '마지막으로 적재한 카탈로그 풀. 한 행(id=1)';
COMMENT ON COLUMN catalog_pool_state.pool_sha256   IS '풀 파일 바이트(압축된 그대로)의 sha256';
COMMENT ON COLUMN catalog_pool_state.malls         IS '적재 당시 merchant.malls[] id 를 쉼표로 이은 것';
COMMENT ON COLUMN catalog_pool_state.pool_limit    IS '적재 당시 merchant.catalog.pool-limit. 0 은 전량';
COMMENT ON COLUMN catalog_pool_state.product_count IS '적재한 풀 상품 수(몰 하나 기준)';
