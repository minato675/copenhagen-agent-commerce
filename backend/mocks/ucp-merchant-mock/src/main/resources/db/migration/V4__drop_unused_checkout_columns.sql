-- checkout_sessions 컬럼 감사. 24개 → 17개
-- 근거: DESIGN.md 5.4절 "저장하지 않는 것", docs/erd/schema-physical.dbml
--
-- 기준은 5.3절과 같다. 계산으로 나오는 것, 다른 컬럼과 중복인 것, 채울 값이 없는 것.
-- V2 는 이미 적용됐으므로 고치지 않고 여기서 뺀다.
--
-- 되살릴 신호는 각 컬럼 주석에 적었다. 다시 넣을 때는 V5 로 ADD COLUMN 한다.

ALTER TABLE checkout_sessions
    -- Core 가 보내지 않는다. 받는 사용자 정보는 fulfillment.destination.recipient 뿐이다(11절).
    -- 불투명 참조값을 보내 주기로 합의되면 되살린다.
    DROP COLUMN buyer_ref,

    -- Complete 멱등 키가 "checkout-complete-" + id 로 확정돼(8.1절) id 에서 계산된다.
    -- UNIQUE 도 PK 와 기능이 겹친다. 재요청 판별은 id 로 행을 찾아
    -- completion_response_json 이 있으면 재생하는 것으로 끝난다.
    DROP COLUMN completion_key,

    -- "같은 키에 다른 바디" 판정용인데, 키가 id 파생이라 그 상황이 곧 재승인 재시도다.
    -- 409 로 막는 것이 정상 흐름을 끊는다는 지적이 8.1절에 있어 판정을 쓸지 자체가 미확정이다.
    DROP COLUMN completion_request_hash,

    -- sha256:base64url(SHA-256(canonical_checkout)) 로 언제든 계산된다(6.3절).
    -- canonical_checkout 은 서명 후 불변이라 값이 흔들리지 않는다. 조회 경로도 인덱스도 없었다.
    DROP COLUMN checkout_digest,

    -- kid 는 merchant_authorization_jws 헤더에 이미 들어 있다.
    -- 검증하는 쪽은 헤더의 kid 로 공개키를 고르지 이 컬럼을 읽지 않는다.
    DROP COLUMN signing_kid,

    -- Pay 응답 전체가 completion_response_json 에 저장돼 재생된다(3.2절). 같은 값이 두 곳이었다.
    DROP COLUMN processor_transaction_id,
    DROP COLUMN payment_error_code;


COMMENT ON COLUMN checkout_sessions.id                       IS 'Mandate 의 business_checkout_id. Complete 멱등 키도 여기서 계산한다';
COMMENT ON COLUMN checkout_sessions.canonical_checkout       IS 'JCS 정규화 결과 문자열. 재정규화하지 않는다. checkoutDigest 의 계산 원본';
COMMENT ON COLUMN checkout_sessions.merchant_authorization_jws IS 'ES256 Detached JWS. 한 체크아웃에 두 번 서명하지 않는다. kid 는 헤더에 있다';
COMMENT ON COLUMN checkout_sessions.completion_response_json IS '재요청 시 결제하지 않고 그대로 재생. 멱등 키가 아니라 id 로 찾는다';
COMMENT ON COLUMN checkout_sessions.version                  IS 'PUT 재계산의 낙관적 락. 상태 전이는 조건부 UPDATE 가 맡는다';
