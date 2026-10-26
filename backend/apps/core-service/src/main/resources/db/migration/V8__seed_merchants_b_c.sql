-- 가상 몰 b·c (S15P21A207-396. Merchant Mock 393·ADR 0008 의 후속).
--
-- V7 이 merchant-a 한 행을 넣었다. Merchant Mock 이 컨테이너 하나에서 몰 셋을 서빙하게 되어
-- 설정 services.merchants[] 에 merchant-b·merchant-c 가 들어왔고, cart_items.merchant_id FK 가
-- 참조할 행이 몰마다 하나씩 필요하다. 고정 PK 2·3 을 설정의 ref-id 가 가리킨다.
--
-- ucp_profile_url·base_url 은 몰 세그먼트가 들어간 새 경로다(Merchant Mock README "경로와 몰 세그먼트").
-- 상호 둘은 Merchant Mock 의 merchant.malls[].seller-name 과 같다. 이름은 임시다.

INSERT INTO merchants (id, merchant_name, ucp_profile_url, base_url, business_profile,
                       active_capabilities, status)
VALUES (2,
        '노을상점',
        'http://ucp-merchant-mock:8080/api/ucp/v1/merchant-b/.well-known/ucp',
        'http://ucp-merchant-mock:8080/api/ucp/v1/merchant-b',
        '{}'::jsonb,
        '[]'::jsonb,
        'ACTIVE'),
       (3,
        '별빛몰',
        'http://ucp-merchant-mock:8080/api/ucp/v1/merchant-c/.well-known/ucp',
        'http://ucp-merchant-mock:8080/api/ucp/v1/merchant-c',
        '{}'::jsonb,
        '[]'::jsonb,
        'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- V7 과 같은 이유. 고정 PK 를 직접 넣었으므로 시퀀스를 그 뒤로 옮긴다.
SELECT setval(pg_get_serial_sequence('merchants', 'id'), (SELECT MAX(id) FROM merchants));
