-- 이슈 410: merchant_accounts에 대응 행이 없어 승인 세션 생성(POST /internal/v1/payment-approvals)이
-- 항상 404였다. AuthorizationSessionService가 merchant_id로 이 테이블을 조회해 없으면
-- RESOURCE_NOT_FOUND를 던지기 때문이다(docker-compose 전체 스택 기동 검증에서 발견).
--
-- Core의 merchants 시드(core-service V7/V8__seed_merchants*.sql)와 같은 목적 — merchant-a/b/c는
-- 이미 Core·Merchant Mock 양쪽에 존재하는 같은 세 몰이다.
--
-- auth_type/credential_ref는 현재 어떤 애플리케이션 코드도 읽지 않는다(JdbcMerchantAccountRepository는
-- merchant_id + status = 'ACTIVE'만 본다) — 인증 토큰이나 Payment Credential이 아니라 존재 확인용
-- placeholder다. 실제 Merchant→Pay 인증은 별도 설정(PAY_AUTH_MERCHANT_SERVICE_TOKENS_JSON)이 맡는다.

-- id는 테스트 스위트 전반이 관례로 쓰는 00000000-0000-0000-0000-000000000001~3(merchant_accounts.id
-- 플레이스홀더)과 겹치지 않도록 별도 대역을 쓴다(Postgres 통합 테스트가 각자 독립 스키마에서 같은 관례
-- id로 자기 fixture를 넣는데, 이 시드가 먼저 그 id를 차지하면 PK 충돌이 난다).
INSERT INTO merchant_accounts (id, merchant_id, display_name, auth_type, status)
VALUES
    ('c9f1a2b3-4d5e-4f61-8a72-9b3c4d5e6f71', 'merchant-a', 'Copenhagen Demo Store', 'TOKEN', 'ACTIVE'),
    ('d0a2b3c4-5e6f-4072-9b83-0c4d5e6f7182', 'merchant-b', '노을상점', 'TOKEN', 'ACTIVE'),
    ('e1b3c4d5-6f70-4183-9c94-1d5e6f718293', 'merchant-c', '별빛몰', 'TOKEN', 'ACTIVE')
ON CONFLICT (merchant_id) DO NOTHING;
