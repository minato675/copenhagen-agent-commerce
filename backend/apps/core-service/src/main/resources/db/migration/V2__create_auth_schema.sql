-- 인증 영역 초기 스키마. core-erd-physical.sql의 users / local_account /
-- oauth_account / refresh_token 정의를 그대로 반영한다.
--
-- users.status는 사용자 전체 계정 상태이며 5회 실패에 따른 일시 잠금과 분리한다.
-- 자체 로그인 5회 실패 시 local_account.locked_until에 현재 시각 + 15분을 저장한다.
-- 잠금 만료 또는 최종 로그인 성공 시 실패 횟수와 잠금만 초기화하고 마지막 실패 기록은 유지한다.
-- last_login_at은 JWT/Refresh Token 발급까지 성공한 최종 로그인 시점에 갱신한다.

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'LOCKED', 'WITHDRAWN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE local_account (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    login_id VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0
        CHECK (failed_login_attempts >= 0),
    last_failed_login_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_local_account_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE oauth_account (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL
        CHECK (provider IN ('google')),
    provider_subject VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_oauth_account_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uq_oauth_account_provider_subject
        UNIQUE (provider, provider_subject),
    CONSTRAINT uq_oauth_account_user_provider
        UNIQUE (user_id, provider)
);

CREATE TABLE refresh_token (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_refresh_token_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_refresh_token_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_local_account_user_id ON local_account(user_id);
CREATE INDEX idx_oauth_account_user_id ON oauth_account(user_id);
CREATE INDEX idx_refresh_token_user_id ON refresh_token(user_id);
CREATE INDEX idx_refresh_token_expires_at ON refresh_token(expires_at);

COMMENT ON TABLE users IS '사용자';
COMMENT ON TABLE local_account IS '자체 로그인 계정';
COMMENT ON TABLE oauth_account IS 'OAuth 계정';
COMMENT ON TABLE refresh_token IS '리프레시 토큰';

COMMENT ON COLUMN users.id IS '사용자식별자';
COMMENT ON COLUMN users.public_id IS '외부사용자식별자';
COMMENT ON COLUMN users.name IS '표시명';
COMMENT ON COLUMN users.status IS '사용자상태';
COMMENT ON COLUMN users.created_at IS '생성시각';
COMMENT ON COLUMN users.updated_at IS '수정시각';

COMMENT ON COLUMN local_account.id IS '자체계정식별자';
COMMENT ON COLUMN local_account.user_id IS '사용자식별자';
COMMENT ON COLUMN local_account.login_id IS '로그인아이디';
COMMENT ON COLUMN local_account.password_hash IS '비밀번호해시';
COMMENT ON COLUMN local_account.password_changed_at IS '비밀번호변경시각';
COMMENT ON COLUMN local_account.failed_login_attempts IS '로그인실패횟수';
COMMENT ON COLUMN local_account.last_failed_login_at IS '마지막로그인실패시각';
COMMENT ON COLUMN local_account.locked_until IS '로그인잠금종료시각';
COMMENT ON COLUMN local_account.last_login_at IS '최종로그인시각';
COMMENT ON COLUMN local_account.created_at IS '생성시각';
COMMENT ON COLUMN local_account.updated_at IS '수정시각';

COMMENT ON COLUMN oauth_account.id IS 'OAuth계정식별자';
COMMENT ON COLUMN oauth_account.user_id IS '사용자식별자';
COMMENT ON COLUMN oauth_account.provider IS '인증제공자';
COMMENT ON COLUMN oauth_account.provider_subject IS '제공자사용자식별자';
COMMENT ON COLUMN oauth_account.email IS '이메일';
COMMENT ON COLUMN oauth_account.email_verified IS '이메일인증여부';
COMMENT ON COLUMN oauth_account.last_login_at IS '최종로그인시각';
COMMENT ON COLUMN oauth_account.created_at IS '생성시각';
COMMENT ON COLUMN oauth_account.updated_at IS '수정시각';

COMMENT ON COLUMN refresh_token.id IS '리프레시토큰식별자';
COMMENT ON COLUMN refresh_token.user_id IS '사용자식별자';
COMMENT ON COLUMN refresh_token.token_hash IS '토큰해시';
COMMENT ON COLUMN refresh_token.expires_at IS '만료시각';
COMMENT ON COLUMN refresh_token.created_at IS '생성시각';
