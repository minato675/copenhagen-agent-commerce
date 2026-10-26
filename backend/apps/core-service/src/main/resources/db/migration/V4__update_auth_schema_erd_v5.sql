-- core ERD v5.1(팀-합의-설계서/core-erd_v*.zip 최신본)에 맞춰 인증 4개 테이블을 개편한다.
--
-- 핵심 변경
--   1) 테이블명 복수화(local_account → local_accounts 등). PostgreSQL은 테이블만 rename하고
--      시퀀스·제약조건·인덱스 이름은 그대로 두므로 부수 객체도 전부 명시적으로 rename한다.
--   2) users의 이메일·연락처를 앱 레벨 암호화 컬럼(BYTEA) + 조회용 결정적 해시 컬럼(CHAR(64))으로 전환한다.
--   3) oauth_accounts.provider 도메인 값을 대문자 'GOOGLE'로 통일한다(V2는 소문자 'google'이었다).
--
-- local_accounts와 refresh_tokens의 컬럼 구성은 V3에서 이미 v5.1과 동일해졌으므로 이름만 바꾼다.
-- 부분 UNIQUE 인덱스(uq_users_email_hash_active 등)는 V6에서 만든다.

-- =========================================================================
-- 0. 데이터 가드
-- =========================================================================
-- PII는 애플리케이션에서 AES-256-GCM으로 암호화하므로 SQL만으로 기존 email/phone_number를
-- email_encrypted/email_hash로 옮길 수 없다. 실 가입자 데이터가 있으면 조용히 날리지 말고 여기서 멈춘다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users) THEN
        RAISE EXCEPTION
            'V4는 users가 비어 있는 상태를 전제한다. 앱 레벨 암호화라 SQL 백필이 불가능하므로 별도 수동 마이그레이션이 필요하다.';
    END IF;
END $$;

-- =========================================================================
-- 1. 테이블명 복수화
-- =========================================================================

ALTER TABLE local_account RENAME TO local_accounts;
ALTER TABLE oauth_account RENAME TO oauth_accounts;
ALTER TABLE refresh_token RENAME TO refresh_tokens;

ALTER SEQUENCE local_account_id_seq RENAME TO local_accounts_id_seq;
ALTER SEQUENCE oauth_account_id_seq RENAME TO oauth_accounts_id_seq;
ALTER SEQUENCE refresh_token_id_seq RENAME TO refresh_tokens_id_seq;

-- local_accounts 제약조건
ALTER TABLE local_accounts RENAME CONSTRAINT local_account_pkey TO local_accounts_pkey;
ALTER TABLE local_accounts RENAME CONSTRAINT local_account_user_id_key TO local_accounts_user_id_key;
ALTER TABLE local_accounts RENAME CONSTRAINT local_account_login_id_key TO local_accounts_login_id_key;
ALTER TABLE local_accounts
    RENAME CONSTRAINT local_account_failed_login_count_check TO local_accounts_failed_login_count_check;
ALTER TABLE local_accounts RENAME CONSTRAINT fk_local_account_user TO fk_local_accounts_user;

-- oauth_accounts 제약조건
ALTER TABLE oauth_accounts RENAME CONSTRAINT oauth_account_pkey TO oauth_accounts_pkey;
ALTER TABLE oauth_accounts RENAME CONSTRAINT fk_oauth_account_user TO fk_oauth_accounts_user;
ALTER TABLE oauth_accounts
    RENAME CONSTRAINT uq_oauth_account_provider_subject TO uq_oauth_accounts_provider_subject;
ALTER TABLE oauth_accounts
    RENAME CONSTRAINT uq_oauth_account_user_provider TO uq_oauth_accounts_user_provider;

-- refresh_tokens 제약조건
ALTER TABLE refresh_tokens RENAME CONSTRAINT refresh_token_pkey TO refresh_tokens_pkey;
ALTER TABLE refresh_tokens RENAME CONSTRAINT refresh_token_token_hash_key TO refresh_tokens_token_hash_key;
ALTER TABLE refresh_tokens RENAME CONSTRAINT fk_refresh_token_user TO fk_refresh_tokens_user;
ALTER TABLE refresh_tokens RENAME CONSTRAINT ck_refresh_token_expiry TO ck_refresh_tokens_expiry;

-- 비제약 인덱스
ALTER INDEX idx_local_account_user_id RENAME TO idx_local_accounts_user_id;
ALTER INDEX idx_oauth_account_user_id RENAME TO idx_oauth_accounts_user_id;
ALTER INDEX idx_refresh_token_user_id RENAME TO idx_refresh_tokens_user_id;
ALTER INDEX idx_refresh_token_expires_at RENAME TO idx_refresh_tokens_expires_at;

COMMENT ON TABLE local_accounts IS '자체 로그인 계정';
COMMENT ON TABLE oauth_accounts IS 'OAuth 계정';
COMMENT ON TABLE refresh_tokens IS '자동 로그인 토큰';

-- =========================================================================
-- 2. users — PII 암호화 컬럼 전환
-- =========================================================================

ALTER TABLE users
    ADD COLUMN email_encrypted BYTEA,
    ADD COLUMN email_hash CHAR(64),
    ADD COLUMN phone_encrypted BYTEA,
    ADD COLUMN phone_hash CHAR(64);

ALTER TABLE users
    DROP CONSTRAINT uq_users_email,
    DROP CONSTRAINT uq_users_phone_number;

ALTER TABLE users
    DROP COLUMN email,
    DROP COLUMN phone_number;

-- 위 가드로 대상 행이 없음이 보장되므로 백필 없이 NOT NULL을 건다.
ALTER TABLE users
    ALTER COLUMN email_encrypted SET NOT NULL,
    ALTER COLUMN email_hash SET NOT NULL;

COMMENT ON COLUMN users.email_encrypted IS '이메일 주소 (RFC 5321 형식 원문을 암호화)';
COMMENT ON COLUMN users.email_hash IS '이메일 중복확인·조회용 결정적 해시 (평문 비교 없이 UNIQUE 판정)';
COMMENT ON COLUMN users.phone_encrypted IS '휴대전화 번호 (E.164 원문을 암호화)';
COMMENT ON COLUMN users.phone_hash IS '연락처 조회용 결정적 해시';

-- =========================================================================
-- 3. oauth_accounts — provider 도메인 값 대문자화
-- =========================================================================
-- V2는 CHECK (provider IN ('google'))로 소문자를 저장했다. ERD v5.1의 도메인 값은 'GOOGLE'이다.

UPDATE oauth_accounts SET provider = UPPER(provider);

ALTER TABLE oauth_accounts
    DROP CONSTRAINT oauth_account_provider_check;

ALTER TABLE oauth_accounts
    ALTER COLUMN provider TYPE VARCHAR(30);

ALTER TABLE oauth_accounts
    ADD CONSTRAINT oauth_accounts_provider_check CHECK (provider IN ('GOOGLE'));
