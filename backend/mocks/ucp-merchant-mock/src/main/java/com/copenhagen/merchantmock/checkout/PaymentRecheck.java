package com.copenhagen.merchantmock.checkout;

/**
 * 재조회 워커가 집어 온 {@code UNKNOWN} 행 하나(Jira 183, DESIGN.md 8.8절).
 *
 * <p>{@link CheckoutSession} 이 아니라 따로 두는 이유는 담는 컬럼이 다르기 때문이다. 저쪽은 172 가 다루는
 * 체크아웃 컬럼이고 여기는 Pay 에 다시 물어보는 데 필요한 것만 담는다.
 *
 * <p>재POST 본문 여섯 중 다섯이 여기 있다. {@code merchantId} 는 행의 몰(393), {@code checkoutHash} 는
 * {@code merchantAuthorizationJws} 를 6.3절대로 해시해서 나온다. 해시를 저장하지 않는 것은 263 컬럼 감사의
 * 결론이고 JWS 가 발급값 그대로 보관되므로(불변 조건 6번) 몇 번을 계산해도 같은 값이 나온다.
 *
 * @param merchantId               이 체크아웃의 몰. Pay 토큰과 본문의 merchantId 가 여기서 나온다
 * @param payPaymentId             있으면 GET 으로 확인하고 없으면 같은 멱등 키로 재POST 한다
 * @param merchantAuthorizationJws checkoutHash 의 계산 원본. 175 전에는 null 이다
 * @param attemptCount             집으면서 올린 뒤의 값. 백오프 계산에 쓴다
 */
public record PaymentRecheck(
        String checkoutId,
        String merchantId,
        String payPaymentId,
        String credentialId,
        String merchantAuthorizationJws,
        Long totalAmount,
        String currency,
        int attemptCount) {

    /** 재POST 를 할 수 있는 행인지. 본문을 되살릴 값이 하나라도 없으면 GET 말고는 길이 없다. */
    public boolean canRepost() {
        return credentialId != null && !credentialId.isBlank()
                && merchantAuthorizationJws != null && !merchantAuthorizationJws.isBlank()
                && totalAmount != null
                && currency != null && !currency.isBlank();
    }
}
