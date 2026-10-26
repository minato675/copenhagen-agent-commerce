-- RFC 9207 발급자 식별을 지원하는 ChatGPT 연결의 고정 콜백.
-- 이미 등록한 개별 콜백·scope·클라이언트 상태는 보존한다. 와일드카드는 허용하지 않는다.
UPDATE oauth_clients
SET redirect_uris = redirect_uris || '["https://chatgpt.com/connector_platform_oauth_redirect"]'::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE client_id = 'chatgpt-plugin'
  AND client_type = 'PUBLIC'
  AND NOT redirect_uris @> '["https://chatgpt.com/connector_platform_oauth_redirect"]'::jsonb;
