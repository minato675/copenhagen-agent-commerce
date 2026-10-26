-- 결제 상태 값을 공유 enum 다섯으로 맞춘다.
-- 근거: DESIGN.md 7.2절, libs/internal-api-contract 의 PaymentStatus
--
-- V2 의 CHECK 는 REQUESTED, SUCCESS, FAILED, UNKNOWN 이었다. REQUESTED 는 어느 문서에도 없던 값이다.
-- 팀 명세, Pay 회신, Pay 코드(MerchantPaymentController) 전부 READY, PROCESSING, SUCCESS, FAILED, UNKNOWN 을 쓴다.
-- 이 컬럼은 Pay 가 돌려준 status 문자열을 그대로 저장하는 자리라 CHECK 가 그 집합과 같아야 한다.
-- 어긋난 채로 두면 Pay 가 PROCESSING 을 돌려주는 순간 결제 도중 500 이 난다.
--
-- 기존 REQUESTED 행은 PROCESSING 으로 옮긴다. 뜻이 같다. 청구를 보냈고 답을 아직 못 받은 상태다.
-- 제약을 먼저 떼고 값을 옮긴 뒤 다시 건다. 순서를 바꾸면 UPDATE 가 옛 제약에, ADD 가 옛 값에 걸린다.

ALTER TABLE checkout_sessions
    DROP CONSTRAINT ck_checkout_payment_status;

UPDATE checkout_sessions
   SET payment_status = 'PROCESSING'
 WHERE payment_status = 'REQUESTED';

ALTER TABLE checkout_sessions
    ADD CONSTRAINT ck_checkout_payment_status CHECK (payment_status IN (
        'READY', 'PROCESSING', 'SUCCESS', 'FAILED', 'UNKNOWN'
    ));

COMMENT ON COLUMN checkout_sessions.payment_status
    IS 'Pay 가 준 status 그대로. 청구 직전 PROCESSING. 갱신은 조건부 UPDATE (IS NULL → PROCESSING, PROCESSING → 결과)';
