-- 순서 25(통합 장바구니, 이슈 S15P21A207-339).
--
-- cart_items.merchant_id 는 merchants(id) FK 인데 merchants 테이블에 행이 하나도 없었다.
-- 순서 24(상품 조회)의 merchant 식별자는 DB 가 아니라 설정값 services.merchants[].id(문자열)라서,
-- 장바구니에 담는 순간 참조할 행이 필요하다.
--
-- 고정 PK 로 넣고 설정의 services.merchants[].ref-id 가 이 id 를 가리킨다.
-- base_url 로 매칭하지 않는 이유는 MERCHANT_SERVICE_URL 오버라이드에 깨지기 때문이다.
--
-- 1행만 넣는다. ucp-merchant-mock 은 merchant.catalog.seed-file 을 하나만 읽으므로
-- daiso/danawa/kurly 는 각각 별개 배포이고, 현재 설정에는 merchant-a 하나뿐이다.
-- merchant 를 늘릴 때 시드 행과 설정 ref-id 를 함께 추가한다.

INSERT INTO merchants (id, merchant_name, ucp_profile_url, base_url, business_profile,
                       active_capabilities, status)
VALUES (1,
        'Copenhagen Demo Store',
        'http://ucp-merchant-mock:8080/.well-known/ucp',
        'http://ucp-merchant-mock:8080',
        '{}'::jsonb,
        '[]'::jsonb,
        'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- 고정 PK 를 직접 넣었으므로 BIGSERIAL 시퀀스를 그 뒤로 옮긴다.
-- 이걸 빼면 다음 자동 INSERT 가 id=1 로 시작해 PK 충돌한다.
SELECT setval(pg_get_serial_sequence('merchants', 'id'), (SELECT MAX(id) FROM merchants));
