-- chk_authorization_sessions_payment_method_state
--   원래 제약은 "결제수단 선택 여부 <=> 상태가 PAYMENT_METHOD_REQUIRED 가 아님" 이라는
--   양방향 조건이었다. 앞 방향("결제수단이 선택됐으면 PAYMENT_METHOD_REQUIRED 일 수 없다")은
--   맞지만, 뒤 방향이 종료 상태까지 적용되는 것이 문제였다.
--
--   REJECTED·EXPIRED·INVALIDATED 는 결제수단 없이 도달하는 것이 정상이다. 사용자가 카드를
--   고르기 전에 만료되면 그냥 만료된다. 그런데 제약이 그 상태의 존재 자체를 금지해서,
--   조회 시 도는 만료 스윕(expireOverdueForUser)이 PAYMENT_METHOD_REQUIRED 행을 EXPIRED 로
--   바꾸려는 순간 위반이 났다. 결과적으로 만료된 미선택 세션을 가진 사용자는 승인 목록과
--   상세 조회가 영구히 500 이었다.
--
--   지켜야 할 불변식("결제수단 없이 승인·결제되면 안 된다")은 PENDING·APPROVED·COMPLETED
--   조건으로 그대로 남긴다. 완화가 아니라 적용 범위를 정확하게 좁히는 변경이다.
--   status 목록은 V3 가 COMPLETED 를 추가한 뒤의 7개를 빠짐없이 덮는다.
ALTER TABLE authorization_sessions
    DROP CONSTRAINT chk_authorization_sessions_payment_method_state;

ALTER TABLE authorization_sessions
    ADD CONSTRAINT chk_authorization_sessions_payment_method_state
    CHECK (
        (status = 'PAYMENT_METHOD_REQUIRED' AND selected_payment_method_id IS NULL)
        OR (status IN ('PENDING', 'APPROVED', 'COMPLETED')
            AND selected_payment_method_id IS NOT NULL)
        OR status IN ('REJECTED', 'EXPIRED', 'INVALIDATED')
    );
