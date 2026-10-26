package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionItem;

/**
 * credential 은 판매처(= checkout) 단위로 발급된다. 사용자 인증은 세션 단위로 1회지만,
 * 승인 트랜잭션 안에서 item 수만큼 호출된다.
 */
@FunctionalInterface
public interface PaymentCredentialIssuer {

    IssuedPaymentCredential issue(
            AuthorizationSession session,
            AuthorizationSessionItem item,
            String authMethod);
}
