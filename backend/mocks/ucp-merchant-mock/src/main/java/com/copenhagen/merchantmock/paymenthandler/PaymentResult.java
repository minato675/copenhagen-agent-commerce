package com.copenhagen.merchantmock.paymenthandler;

import com.copenhagen.contract.internalapi.PaymentStatus;

/**
 * Pay 청구의 결과. 응답 본문 {@code {paymentId, status, pgTransactionId?, errorCode?, nextAction?}} 을
 * 옮긴 것이고, 응답을 못 받은 경우도 같은 모양으로 {@code UNKNOWN} 을 돌려준다(DESIGN.md 8.5절).
 *
 * <p>{@code paymentId} 는 상태가 {@code UNKNOWN} 이어도 있으면 채운다. 183 워커가 재조회할 열쇠다.
 * 없는 값은 {@code null} 이다. 이 record 는 응답에 직렬화되지 않으므로 17절 "null 금지" 의 대상이 아니다.
 */
public record PaymentResult(
        PaymentStatus status,
        String paymentId,
        String pgTransactionId,
        String errorCode,
        String nextAction) {

    public PaymentResult {
        if (status == null) {
            throw new IllegalArgumentException("결제 상태가 비어 있다");
        }
    }

    /** Pay 가 처리하지 않은 것이 확실한 실패. 4xx 가 여기로 온다. */
    static PaymentResult failed(String errorCode) {
        return new PaymentResult(PaymentStatus.FAILED, null, null, errorCode, null);
    }

    /** 처리됐는지 모르는 상태. 5xx·타임아웃·연결 실패·깨진 본문이 여기로 온다. */
    static PaymentResult unknown(String errorCode) {
        return new PaymentResult(PaymentStatus.UNKNOWN, null, null, errorCode, null);
    }

    public boolean isSuccess() {
        return status == PaymentStatus.SUCCESS;
    }
}
