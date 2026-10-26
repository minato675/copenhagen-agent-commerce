-- Notion ERD v4(2026-09-13, erd_v4.zip)에 맞춰 인증 4개 테이블을 갱신한다.
-- 전제: 이 마이그레이션을 적용하는 시점에 users/local_account/oauth_account/refresh_token에
-- 실 가입자 데이터가 없다(자체 회원가입 API 미병합, 로그인/토큰 발급 API 미구현).
-- 그래도 이동되는 컬럼(email, last_login_at, password_changed_at)은 안전하게 백필한 뒤 옮긴다.

-- =========================================================================
-- 1. users
-- =========================================================================

-- 기존 LOCKED 값이 있다면 SUSPENDED로 선이동(방어적 처리, 보통 대상 행 없음)
UPDATE users SET status = 'SUSPENDED' WHERE status = 'LOCKED';

ALTER TABLE users
    ALTER COLUMN name TYPE VARCHAR(50);

ALTER TABLE users
    DROP CONSTRAINT users_status_check;

ALTER TABLE users
    ADD CONSTRAINT users_status_check
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN'));

ALTER TABLE users
    ADD COLUMN email VARCHAR(254),
    ADD COLUMN phone_number VARCHAR(20),
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN pw_changed_at TIMESTAMPTZ,
    ADD COLUMN last_login_at TIMESTAMPTZ;

-- oauth_account에만 있던 이메일 정보를 users로 백필한다.
-- (local_account만 있고 oauth_account가 없는 사용자는 백필 소스가 없다)
UPDATE users u
SET email = oa.email,
    email_verified = oa.email_verified
FROM oauth_account oa
WHERE oa.user_id = u.id
  AND u.email IS NULL
  AND oa.email IS NOT NULL;

-- local_account/oauth_account에 흩어져 있던 최종 로그인 시각 중 가장 최근 값을 users로 옮긴다.
UPDATE users u
SET last_login_at = merged.last_login_at
FROM (
    SELECT user_id, MAX(last_login_at) AS last_login_at
    FROM (
        SELECT user_id, last_login_at FROM local_account WHERE last_login_at IS NOT NULL
        UNION ALL
        SELECT user_id, last_login_at FROM oauth_account WHERE last_login_at IS NOT NULL
    ) combined
    GROUP BY user_id
) merged
WHERE merged.user_id = u.id;

-- local_account.password_changed_at을 users.pw_changed_at으로 옮긴다.
UPDATE users u
SET pw_changed_at = la.password_changed_at
FROM local_account la
WHERE la.user_id = u.id;

ALTER TABLE users
    ALTER COLUMN email SET NOT NULL,
    ADD CONSTRAINT uq_users_email UNIQUE (email),
    ADD CONSTRAINT uq_users_phone_number UNIQUE (phone_number);

COMMENT ON COLUMN users.email IS '이메일';
COMMENT ON COLUMN users.phone_number IS '휴대전화번호';
COMMENT ON COLUMN users.email_verified IS '이메일인증여부';
COMMENT ON COLUMN users.deleted_at IS '회원탈퇴시각';
COMMENT ON COLUMN users.pw_changed_at IS '비밀번호변경시각';
COMMENT ON COLUMN users.last_login_at IS '최종로그인시각';

-- =========================================================================
-- 2. local_account
-- =========================================================================

ALTER TABLE local_account
    ALTER COLUMN login_id TYPE VARCHAR(20);

ALTER TABLE local_account
    RENAME COLUMN failed_login_attempts TO failed_login_count;

ALTER TABLE local_account
    RENAME CONSTRAINT local_account_failed_login_attempts_check
        TO local_account_failed_login_count_check;

ALTER TABLE local_account
    ALTER COLUMN failed_login_count TYPE SMALLINT;

ALTER TABLE local_account
    DROP COLUMN password_changed_at,
    DROP COLUMN last_login_at;

-- =========================================================================
-- 3. oauth_account
-- =========================================================================

ALTER TABLE oauth_account
    DROP COLUMN email,
    DROP COLUMN email_verified,
    DROP COLUMN last_login_at;

-- provider_subject NOT NULL, uq_oauth_account_provider_subject UNIQUE(provider, provider_subject),
-- uq_oauth_account_user_provider UNIQUE(user_id, provider)는 V2에 이미 존재하며 이번 ERD 반영
-- 대상이 아니다. 둘 다 유지한다(2026-09-14 확정 — 개발용-추가-문서/my_files/구현설계_S15P21A207-279_
-- 인증스키마-ERD갱신.md §1 C-5 참고. 서로 다른 무결성 규칙을 지키므로 하나로 대체할 수 없다).

-- =========================================================================
-- 4. refresh_token
-- =========================================================================

ALTER TABLE refresh_token
    ALTER COLUMN token_hash TYPE CHAR(64);

ALTER TABLE refresh_token
    ADD COLUMN device_id VARCHAR(255) NOT NULL,
    ADD COLUMN device_name VARCHAR(255) NOT NULL,
    ADD COLUMN issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN last_used_at TIMESTAMPTZ;

COMMENT ON COLUMN refresh_token.device_id IS '기기식별자';
COMMENT ON COLUMN refresh_token.device_name IS '기기표시명';
COMMENT ON COLUMN refresh_token.issued_at IS '발급시각';
COMMENT ON COLUMN refresh_token.revoked_at IS '폐기시각';
COMMENT ON COLUMN refresh_token.last_used_at IS '최근사용시각';
