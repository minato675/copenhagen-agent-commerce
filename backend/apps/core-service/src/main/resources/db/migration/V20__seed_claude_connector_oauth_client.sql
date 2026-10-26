-- Claude 커스텀 커넥터(PUBLIC, PKCE) — 이슈 S15P21A207-451.
-- 콜백은 Anthropic 공식 문서 "Authentication for connectors"의 Callback URLs 절이 지정한 주소다.
-- claude.ai 웹·데스크톱·모바일이 같은 주소를 쓴다. 와일드카드나 접두사 일치는 등록하지 않는다.
-- 루프백(Claude Code)은 세션마다 포트가 달라 완전 일치 검증에 걸리므로 여기서 다루지 않는다.
-- chatgpt-plugin 행과 기존 ChatGPT 연결에는 손대지 않는다.
INSERT INTO oauth_clients (client_id, client_secret_hash, client_name, client_type,
                           redirect_uris, allowed_scopes, logo_url, status)
VALUES ('claude-connector', NULL, 'Claude', 'PUBLIC',
        '["https://claude.ai/api/mcp/auth_callback"]'::jsonb,
        '["catalog:read","cart:read","cart:write","purchase:handoff"]'::jsonb,
        NULL, 'ACTIVE')
ON CONFLICT (client_id) DO NOTHING;
