package com.copenhagen.core.payment;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.contract.internalapi.ApprovalStatus;
import com.copenhagen.contract.internalapi.PaymentApprovalItem;
import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.checkout.MerchantCheckoutClient;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CompleteResult;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.OrderRef;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.PaymentRef;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.AwaitingApprovalCheckout;
import com.copenhagen.core.product.MerchantsProperties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 승인 1건이 판매처 N곳을 갖는 폴링(이슈 S15P21A207-486). 두 checkout이 같은 {@code approvalId}를
 * 공유하고, credential은 각자 것을 쓴다.
 */
class PaymentApprovalPollingWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final String APPROVAL_ID = "approval-1";

    private final AwaitingApprovalCheckout rowA = new AwaitingApprovalCheckout(
            1L, 10L, 3L, 1L, "checkout-a", APPROVAL_ID, NOW.plusSeconds(300), 4L, 10000L, "KRW");
    private final AwaitingApprovalCheckout rowB = new AwaitingApprovalCheckout(
            2L, 10L, 3L, 2L, "checkout-b", APPROVAL_ID, NOW.plusSeconds(300), 5L, 20000L, "KRW");

    private final MerchantCheckoutClient merchantA = mock(MerchantCheckoutClient.class);
    private final MerchantCheckoutClient merchantB = mock(MerchantCheckoutClient.class);
    private final MerchantCheckoutRepository repository = mock(MerchantCheckoutRepository.class);
    private final PaymentApprovalClient approvals = mock(PaymentApprovalClient.class);
    private final PaymentCompletionRepository transactions = mock(PaymentCompletionRepository.class);
    private final PaymentCompletionService completion = new PaymentCompletionService(
            new CartMerchants(new MerchantsProperties(List.of(
                    merchant("merchant-a", 1L), merchant("merchant-b", 2L)))),
            Map.of("merchant-a", merchantA, "merchant-b", merchantB),
            transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("같은 승인을 공유하는 checkout이 여러 건이어도 Pay 상태 조회는 한 번이다")
    void sharedApproval_queriesPayOnce() {
        when(repository.findCompleting(NOW)).thenReturn(List.of());
        when(repository.findAwaitingApproval()).thenReturn(List.of(rowA, rowB));
        when(repository.beginCompleting(any(Long.class), any())).thenReturn(true);
        when(approvals.status(APPROVAL_ID)).thenReturn(approved());
        when(merchantA.complete(any(), any(), any())).thenReturn(completed("checkout-a", "order-a", "payment-a"));
        when(merchantB.complete(any(), any(), any())).thenReturn(completed("checkout-b", "order-b", "payment-b"));

        poll();

        verify(approvals, times(1)).status(APPROVAL_ID);
    }

    @Test
    @DisplayName("판매처마다 자기 credential로 Complete Checkout을 부른다")
    void sharedApproval_usesPerMerchantCredential() {
        when(repository.findCompleting(NOW)).thenReturn(List.of());
        when(repository.findAwaitingApproval()).thenReturn(List.of(rowA, rowB));
        when(repository.beginCompleting(any(Long.class), any())).thenReturn(true);
        when(approvals.status(APPROVAL_ID)).thenReturn(approved());
        when(merchantA.complete(any(), any(), any())).thenReturn(completed("checkout-a", "order-a", "payment-a"));
        when(merchantB.complete(any(), any(), any())).thenReturn(completed("checkout-b", "order-b", "payment-b"));

        poll();

        verify(merchantA).complete("checkout-a", "credential-a", "checkout-complete-checkout-a");
        verify(merchantB).complete("checkout-b", "credential-b", "checkout-complete-checkout-b");
        verify(repository).markCompleted(1L, NOW);
        verify(repository).markCompleted(2L, NOW);
    }

    @Test
    @DisplayName("승인 거부는 세션 단위라 같은 승인의 checkout 전부를 취소한다")
    void rejectedApproval_cancelsEveryCheckout() {
        when(repository.findCompleting(NOW)).thenReturn(List.of());
        when(repository.findAwaitingApproval()).thenReturn(List.of(rowA, rowB));
        when(approvals.status(APPROVAL_ID)).thenReturn(new PaymentApprovalStatusResponse(
                APPROVAL_ID, ApprovalStatus.REJECTED, null, null, 30000L, "KRW", null, null, null, null, List.of()));

        poll();

        verify(repository).markCancelled(1L, "USER_REJECTED", NOW);
        verify(repository).markCancelled(2L, "USER_REJECTED", NOW);
    }

    @Test
    @DisplayName("응답에 자기 checkout이 없으면 다른 판매처 credential로 결제하지 않고 건너뛴다")
    void missingItem_doesNotCompleteWithAnotherMerchantsCredential() {
        when(repository.findCompleting(NOW)).thenReturn(List.of());
        when(repository.findAwaitingApproval()).thenReturn(List.of(rowB));
        when(approvals.status(APPROVAL_ID)).thenReturn(new PaymentApprovalStatusResponse(
                APPROVAL_ID, ApprovalStatus.APPROVED, null, null, 30000L, "KRW", null, null, null, null,
                List.of(new PaymentApprovalItem("merchant-a", "checkout-a", "credential-a", NOW.plusSeconds(300),
                        null, null, "카드"))));

        poll();

        verify(merchantB, never()).complete(any(), any(), any());
        verify(repository, never()).beginCompleting(any(Long.class), any());
    }

    private void poll() {
        new PaymentApprovalPollingWorker(repository, approvals, completion, Clock.fixed(NOW, ZoneOffset.UTC)).poll();
    }

    private static PaymentApprovalStatusResponse approved() {
        return new PaymentApprovalStatusResponse(APPROVAL_ID, ApprovalStatus.APPROVED, null, null,
                30000L, "KRW", null, null, null, null, List.of(
                        new PaymentApprovalItem("merchant-a", "checkout-a", "credential-a",
                                NOW.plusSeconds(300), null, null, "카드"),
                        new PaymentApprovalItem("merchant-b", "checkout-b", "credential-b",
                                NOW.plusSeconds(300), null, null, "카드")));
    }

    private static CompleteResult completed(String checkoutId, String orderId, String paymentId) {
        return new CompleteResult(checkoutId, "completed", new OrderRef(orderId, null),
                new PaymentRef(paymentId, "SUCCESS", null));
    }

    private static MerchantsProperties.MerchantConfig merchant(String id, long refId) {
        return new MerchantsProperties.MerchantConfig(id, refId, id.toUpperCase(),
                URI.create("http://" + id + ":8080"), Duration.ofSeconds(1), Duration.ofSeconds(3));
    }
}
