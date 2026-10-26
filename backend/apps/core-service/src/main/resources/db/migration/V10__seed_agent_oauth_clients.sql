-- ChatGPT 플러그인(PUBLIC, PKCE) — 사용자 결정(이슈 S15P21A207-337).
-- redirect_uri는 실제 ChatGPT Actions 콜백 URL이 정해지면 UPDATE 필요(현재 placeholder).
INSERT INTO oauth_clients (client_id, client_secret_hash, client_name, client_type,
                           redirect_uris, allowed_scopes, logo_url, status)
VALUES ('chatgpt-plugin', NULL, 'ChatGPT', 'PUBLIC',
        '["https://chat.openai.com/aip/PLACEHOLDER/oauth/callback"]'::jsonb,
        '["catalog:read","cart:read","cart:write","purchase:handoff"]'::jsonb,
        NULL, 'ACTIVE')
ON CONFLICT (client_id) DO NOTHING;

-- copenhagen-mcp 서비스 클라이언트(CONFIDENTIAL, private_key_jwt) — mcp-server 이슈400 계약.
-- client_jwks는 MCP가 실제 공개키를 공유한 뒤 운영에서 UPDATE로 채운다(이 세션에서 알 수 없는 값).
-- 그때까지는 client_credentials 요청이 항상 실패한다 — 의도된 동작.
INSERT INTO oauth_clients (client_id, client_secret_hash, client_name, client_type,
                           redirect_uris, allowed_scopes, logo_url, status, client_jwks)
VALUES ('copenhagen-mcp', NULL, 'Copenhagen MCP Server', 'CONFIDENTIAL',
        '[]'::jsonb,
        '["catalog:read","cart:read","cart:write","purchase:handoff"]'::jsonb,
        NULL, 'ACTIVE', NULL)
ON CONFLICT (client_id) DO NOTHING;
