-- 이슈 S15P21A207-337. Authorization Code 저장(사용자 결정) + PKCE(사용자 결정) +
-- copenhagen-mcp 서비스 클라이언트의 private_key_jwt 인증(mcp-server 이슈400 계약).

-- 1) Authorization Code의 생애주기(로그인 전 대기 → 승인/거부 → 코드 소비)를 한 행에 담는다.
--    별도 "동의 세션" 테이블을 두지 않는다 — Core가 직접 화면을 서빙하므로 세션과 코드가
--    같은 요청 하나의 상태 전이일 뿐이다.
CREATE TABLE oauth_authorization_requests (
    id BIGSERIAL PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    oauth_client_id BIGINT NOT NULL REFERENCES oauth_clients(id),
    redirect_uri VARCHAR(2048) NOT NULL,
    requested_scopes JSONB NOT NULL,
    state VARCHAR(255) NOT NULL,
    code_challenge VARCHAR(255) NOT NULL,
    code_challenge_method VARCHAR(10) NOT NULL CHECK (code_challenge_method = 'S256'),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'DENIED', 'CONSUMED', 'EXPIRED')),
    user_id BIGINT REFERENCES users(id),
    code_hash CHAR(64),
    code_expires_at TIMESTAMPTZ,
    request_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_oauth_authorization_requests_code_hash
    ON oauth_authorization_requests (code_hash)
    WHERE code_hash IS NOT NULL;

COMMENT ON TABLE oauth_authorization_requests IS 'Agent OAuth 인가 요청(로그인 대기~코드 발급까지)';
COMMENT ON COLUMN oauth_authorization_requests.request_id IS '브라우저 폼에 노출되는 opaque 식별자';
COMMENT ON COLUMN oauth_authorization_requests.code_hash IS 'SHA-256 해시. 원문은 저장하지 않음';
COMMENT ON COLUMN oauth_authorization_requests.code_expires_at IS 'APPROVED 시점 + 60초';
COMMENT ON COLUMN oauth_authorization_requests.request_expires_at IS '생성 시점 + 10분(로그인+동의 제한시간)';

-- 2) copenhagen-mcp 같은 CONFIDENTIAL 클라이언트의 private_key_jwt 인증용 공개키(JWK Set).
--    client_secret_hash와는 별개 컬럼 — 시크릿 기반과 비대칭키 기반 인증은 저장 형태가 다르다.
ALTER TABLE oauth_clients ADD COLUMN client_jwks JSONB;
COMMENT ON COLUMN oauth_clients.client_jwks IS
    'private_key_jwt 인증용 공개키(JWK Set). client_secret_hash와 상호 배타적';

-- 3) client_credentials + private_key_jwt의 재전송(replay) 방지.
--    RFC 7523 client assertion은 매 요청 새 jti를 요구하므로, 같은 (client, jti)의 재사용을 막는다.
CREATE TABLE oauth_client_assertion_jtis (
    oauth_client_id BIGINT NOT NULL REFERENCES oauth_clients(id),
    jti VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (oauth_client_id, jti)
);
COMMENT ON TABLE oauth_client_assertion_jtis IS
    'private_key_jwt client assertion 재사용 방지. expires_at 지난 행은 방치 허용(assertion TTL이 짧아 행 수가 적음)';
