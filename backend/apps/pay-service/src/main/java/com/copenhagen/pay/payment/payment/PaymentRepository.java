package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.util.Optional;

public interface PaymentRepository {

    Optional<PaymentStatus> findStatus(String paymentId);

    Optional<String> findAuthorizationSessionId(String paymentId);

    /** 결제 결과는 세션이 아니라 판매처별 item 이 받는다. */
    Optional<String> findSessionItemId(String paymentId);

    boolean existsLiveByPurchaseRequest(String merchantAccountId, String purchaseRequestId);

    boolean transitionStatus(String paymentId, PaymentStatus expectedStatus, PaymentStatus targetStatus);
}
