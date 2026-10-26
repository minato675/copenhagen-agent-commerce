package com.copenhagen.merchantmock.paymenthandler;

/**
 * Pay 에 결제를 청구하는 경계(Jira 179). 구현은 {@link HttpPayClient}. 테스트는 가짜 구현을 넣는다.
 *
 * <p>전송 실패에 예외를 던지지 않는다. 타임아웃·연결 실패·5xx·깨진 본문은 전부 {@code UNKNOWN} 결과로
 * 돌아온다(DESIGN.md 8.5절). 부르는 쪽이 "예외면 재시도" 로 흐르지 않게 하기 위해서다. 결제는 재청구하지
 * 않는다(불변 조건 2번).
 */
public interface PayClient {

    PaymentResult charge(PaymentRequest request);

    /**
     * {@code GET /merchant/v1/payments/{paymentId}}. 183 워커가 {@code paymentId} 를 아는 UNKNOWN 을 확인할 때
     * 쓴다(DESIGN.md 8.8절). {@code charge} 와 같은 약속이다 — 전송 실패에 예외를 던지지 않고 UNKNOWN 을 준다.
     *
     * @param merchantId 어느 몰의 토큰으로 물어보나(393). Pay 는 토큰의 몰과 결제의 몰이 다르면 거절한다
     */
    PaymentResult lookup(String merchantId, String paymentId);
}
