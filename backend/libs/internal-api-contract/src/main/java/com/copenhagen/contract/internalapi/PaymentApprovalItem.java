package com.copenhagen.contract.internalapi;

import java.time.Instant;

/**
 * 승인 세션에 묶인 판매처 한 곳의 결과(이슈 S15P21A207-486). 승인 단위가 구매 요청으로 올라가면서
 * 승인 1건이 checkout N개를 갖는다. credential과 결제는 여전히 checkout당 1건이므로, 어느 판매처의
 * 값인지는 {@code merchantId}·{@code checkoutId}로 식별한다.
 */
public record PaymentApprovalItem(
        String merchantId,
        String checkoutId,
        String credentialId,
        Instant credentialExpiresAt,
        String paymentId,
        PaymentStatus paymentStatus,
        String paymentLabel) {
}
