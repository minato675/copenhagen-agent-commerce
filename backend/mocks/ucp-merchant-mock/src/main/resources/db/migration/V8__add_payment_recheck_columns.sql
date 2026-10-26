-- UNKNOWN 재조회 워커(183)가 쓸 컬럼 둘과 인덱스 교체.
-- 근거: DESIGN.md 8.8절, 5.4절
--
-- payment_credential_id
--   재POST 본문을 되살리는 데 필요한 유일한 값이다. 나머지 다섯은 이미 있다 —
--   merchantId 는 설정, businessCheckoutId 는 id, amountMinor·currency 는 컬럼,
--   checkoutHash 는 merchant_authorization_jws 에서 계산한다(6.3절).
--   Credential 은 Core 가 승인 한 건마다 발급하므로 내가 만들어 낼 수 없다.
--   멱등 키 merchant-payment-{credentialId} 도 이 값에서 나온다(8.2절).
--
-- payment_next_attempt_at
--   "다음에 언제 다시 볼지". payment_requested_at 을 재사용하지 않는다.
--   그 컬럼은 "Pay 에 청구를 언제 보냈나" 이고 UNKNOWN 행에서는 돈이 움직였을 수 있는
--   시각의 유일한 단서다. 워커가 스캔할 때마다 덮어쓰면 그 단서가 사라진다.
--   webhook_deliveries.next_attempt_at 과 같은 역할이다.

ALTER TABLE checkout_sessions
    ADD COLUMN payment_credential_id   varchar(100),
    ADD COLUMN payment_next_attempt_at timestamptz;

-- 워커가 타는 경로가 payment_requested_at 에서 payment_next_attempt_at 으로 바뀐다.
-- 옛 인덱스는 읽는 곳이 없어진다.
DROP INDEX ix_checkout_payment;
CREATE INDEX ix_checkout_payment ON checkout_sessions (payment_status, payment_next_attempt_at);

COMMENT ON COLUMN checkout_sessions.payment_credential_id
    IS 'Pay 재POST 본문과 멱등 키 재현용. 청구 직전 beginPayment 가 적는다(DESIGN.md 8.8절)';
COMMENT ON COLUMN checkout_sessions.payment_next_attempt_at
    IS '183 워커가 이 행을 다음에 볼 시각. 지수 백오프. 확정되면 NULL';
COMMENT ON COLUMN checkout_sessions.payment_requested_at
    IS 'Pay 에 청구를 보낸 시각. 워커가 덮어쓰지 않는다. UNKNOWN 행의 유일한 청구 시각 단서다';
COMMENT ON COLUMN checkout_sessions.payment_attempt_count
    IS '재조회 시도 횟수. 재청구 횟수가 아니다. 한도를 넘으면 워커가 스캔에서 뺀다';
