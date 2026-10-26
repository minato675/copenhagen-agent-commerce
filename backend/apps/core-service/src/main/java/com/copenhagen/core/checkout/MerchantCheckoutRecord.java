package com.copenhagen.core.checkout;

import java.util.List;

/**
 * merchant 주문서 한 건과 그 승인 대상 스냅샷을 묶은 저장 단위.
 *
 * <p>둘을 따로 저장하면 주문서만 남고 스냅샷이 없는 상태가 생길 수 있고, 그 상태로는 사용자가 무엇을
 * 승인하는지 고정할 수 없다. 한 트랜잭션으로 함께 넣는다.
 */
public record MerchantCheckoutRecord(
        MerchantCheckout checkout,
        CheckoutSnapshot snapshot,
        List<CheckoutSnapshotItem> items,
        List<CheckoutFulfillmentOption> fulfillmentOptions) {
}
