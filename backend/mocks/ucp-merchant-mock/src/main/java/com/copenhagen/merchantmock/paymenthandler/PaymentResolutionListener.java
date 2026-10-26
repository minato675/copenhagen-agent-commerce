package com.copenhagen.merchantmock.paymenthandler;

import com.copenhagen.contract.internalapi.PaymentStatus;

/**
 * 결제 UNKNOWN 이 늦게 확정됐을 때 듣는 쪽(DESIGN.md 8.8절, Jira 176). 구현은 {@code checkout/CheckoutCompletionService}.
 *
 * <p>{@link PaymentRecheckWorker} 가 {@code resolvePayment} 를 1행 바꾼 직후 부른다. SUCCESS 면 주문을 만들고
 * 체크아웃을 {@code completed} 로, FAILED 면 {@code ready_for_complete} 로 되돌리는 것이 구현의 몫이다.
 * 인터페이스를 여기 두는 이유는 워커가 체크아웃 완료 코드를 직접 알지 않게 하기 위해서다.
 */
public interface PaymentResolutionListener {

    void paymentResolved(String checkoutId, PaymentStatus status, String payPaymentId);
}
