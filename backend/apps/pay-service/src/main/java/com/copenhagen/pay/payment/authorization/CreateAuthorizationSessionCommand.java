package com.copenhagen.pay.payment.authorization;

import java.util.List;

/**
 * 구매 요청 하나에 대한 승인 세션 생성 명령. 판매처가 몇 곳이든 세션은 1개이며
 * 판매처별 주문서는 {@link Item} 으로 전달된다.
 */
public record CreateAuthorizationSessionCommand(
        String purchaseRequestId,
        String userId,
        long totalAmountMinor,
        String currency,
        List<Item> items) {

    public CreateAuthorizationSessionCommand {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(
            String merchantId,
            String checkoutId,
            String checkoutHash,
            String checkoutJwt,
            AuthorizationDisplaySnapshot displaySnapshot,
            long amountMinor,
            String currency) {
    }
}
