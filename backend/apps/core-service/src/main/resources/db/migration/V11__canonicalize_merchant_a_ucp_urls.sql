-- 이슈 409: Merchant Mock 393 이후의 canonical 몰별 UCP 경로를 Core 시드에도 반영한다.
-- V7의 legacy 경로는 LegacyPathFilter가 유지하지만, Discovery 정상 경로에서는 쓰지 않는다.

UPDATE merchants
SET ucp_profile_url = 'http://ucp-merchant-mock:8080/api/ucp/v1/merchant-a/.well-known/ucp',
    base_url = 'http://ucp-merchant-mock:8080/api/ucp/v1/merchant-a',
    profile_expires_at = NULL
WHERE id = 1
  AND ucp_profile_url = 'http://ucp-merchant-mock:8080/.well-known/ucp';
