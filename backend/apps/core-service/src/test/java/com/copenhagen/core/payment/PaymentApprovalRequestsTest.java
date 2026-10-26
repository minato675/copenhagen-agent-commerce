package com.copenhagen.core.payment;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.checkout.CheckoutDigests;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Item;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.LineItem;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Total;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;
import com.copenhagen.core.payment.PaymentApprovalRequests.ApprovalItemInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentApprovalRequestsTest {

    private static final String PURCHASE_REQUEST_ID = UUID.randomUUID().toString();
    private static final String USER_ID = UUID.randomUUID().toString();

    @Test
    @DisplayName("멱등키는 구매 요청만으로 결정된다 — 판매처가 섞이면 재시도가 승인 세션을 새로 만든다")
    void idempotencyKey_dependsOnlyOnPurchaseRequest() {
        String first = PaymentApprovalRequests.idempotencyKey(PURCHASE_REQUEST_ID);

        assertThat(first).isEqualTo(PaymentApprovalRequests.idempotencyKey(PURCHASE_REQUEST_ID));
        assertThat(UUID.fromString(first)).isNotNull();
        assertThat(first).isNotEqualTo(PaymentApprovalRequests.idempotencyKey(UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("판매처 전부를 items에 담고 총액은 판매처 합이다")
    void create_batchesEveryMerchantAndSumsTotal() {
        CreateApprovalRequest request = PaymentApprovalRequests.create(PURCHASE_REQUEST_ID, USER_ID, List.of(
                input("merchant-a", "checkout-a", 10000L),
                input("merchant-b", "checkout-b", 20000L)));

        assertThat(request.purchaseRequestId()).isEqualTo(PURCHASE_REQUEST_ID);
        assertThat(request.userId()).isEqualTo(USER_ID);
        assertThat(request.totalAmountMinor()).isEqualTo(30000L);
        assertThat(request.currency()).isEqualTo("KRW");
        assertThat(request.items()).hasSize(2);
        assertThat(request.items().get(0).merchantId()).isEqualTo("merchant-a");
        assertThat(request.items().get(0).amountMinor()).isEqualTo(10000L);
        assertThat(request.items().get(1).checkoutId()).isEqualTo("checkout-b");
        // Pay는 hex가 아니라 "sha256:"+Base64URL 외부 표현만 받는다.
        assertThat(request.items().get(0).checkoutHash()).startsWith("sha256:");
    }

    @Test
    @DisplayName("승인 대상이 없으면 빈 세션을 만들지 않는다")
    void create_rejectsEmptyItems() {
        assertThatThrownBy(() -> PaymentApprovalRequests.create(PURCHASE_REQUEST_ID, USER_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ApprovalItemInput input(String merchantId, String checkoutId, long total) {
        Checkout checkout = new Checkout(checkoutId, "ready_for_complete", "KRW",
                List.of(new LineItem("line-1", new Item("p-1", "상품", total), 1)),
                null, List.of(new Total("total", total)), List.of(),
                "2026-01-01T00:15:00Z", "jws." + checkoutId + ".sig");
        return new ApprovalItemInput(merchantId, merchantId.toUpperCase(),
                CheckoutDigests.of(checkout.merchantAuthorization()), checkout);
    }
}
