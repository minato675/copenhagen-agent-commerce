package com.copenhagen.core.payment;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.copenhagen.contract.internalapi.ApprovalStatus;
import com.copenhagen.contract.internalapi.PaymentApprovalItem;
import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.checkout.MerchantCheckoutClient;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CompleteResult;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.OrderRef;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.PaymentRef;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.AwaitingApprovalCheckout;
import com.copenhagen.core.product.MerchantsProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private final AwaitingApprovalCheckout row = new AwaitingApprovalCheckout(
            1L, 2L, 3L, 1L, "checkout-1", "approval-1", NOW.minusSeconds(60), 4L, 19600L, "KRW");
    private final MerchantCheckoutClient merchant = mock(MerchantCheckoutClient.class);
    private final PaymentCompletionRepository transactions = mock(PaymentCompletionRepository.class);
    private final PaymentCompletionService completion = new PaymentCompletionService(
            new CartMerchants(new MerchantsProperties(List.of(new MerchantsProperties.MerchantConfig(
                    "merchant-a", 1L, "몰", URI.create("http://merchant:8080"),
                    Duration.ofSeconds(1), Duration.ofSeconds(3))))),
            Map.of("merchant-a", merchant), transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void timeoutAfterMerchantCommit_recoveryReadsExistingOrderWithoutChargingAgain() {
        when(merchant.complete("checkout-1", "credential-1", "checkout-complete-checkout-1"))
                .thenThrow(new IllegalStateException("response lost"));
        assertThrows(IllegalStateException.class, () -> completion.complete(row, "credential-1", "카드"));
        when(merchant.current("checkout-1")).thenReturn(completed());
        assertEquals(PaymentCompletionService.Outcome.COMPLETED, completion.recover(row, status()));
        verify(transactions).recordCompleted(4L, 3L, "approval-1", "order-1", "payment-1",
                19600L, "KRW", "카드", NOW);
        // complete는 응답 유실 전 최초 1회뿐이다.
        verify(merchant).complete("checkout-1", "credential-1", "checkout-complete-checkout-1");
    }

    @Test
    void failedBeforePayTransaction_recordsFailureWithoutInventingTransactionId() {
        when(merchant.complete("checkout-1", "credential-1", "checkout-complete-checkout-1"))
                .thenReturn(new CompleteResult("checkout-1", "ready_for_complete", null,
                        new PaymentRef(null, "FAILED", "INVALID_CREDENTIAL")));
        assertEquals(PaymentCompletionService.Outcome.FAILED, completion.complete(row, "credential-1", null));
        verify(transactions).recordFailed(4L, "approval-1", null, 19600L, "KRW", "INVALID_CREDENTIAL", NOW);
    }

    @Test
    void pollingCompletingCheckout_recoversEvenWhenApprovalDeadlineHasPassed() {
        MerchantCheckoutRepository repository = mock(MerchantCheckoutRepository.class);
        PaymentApprovalClient approvals = mock(PaymentApprovalClient.class);
        when(repository.findCompleting(NOW)).thenReturn(List.of(row));
        when(repository.findAwaitingApproval()).thenReturn(List.of());
        when(repository.beginCompleting(1L, NOW)).thenReturn(true);
        when(approvals.status("approval-1")).thenReturn(status());
        when(merchant.current("checkout-1")).thenReturn(completed());
        new PaymentApprovalPollingWorker(repository, approvals, completion, Clock.fixed(NOW, ZoneOffset.UTC)).poll();
        verify(repository).markCompleted(1L, NOW);
        verify(repository, never()).markExpired(1L, NOW);
        verify(merchant, never()).complete(any(), any(), any());
    }

    private CompleteResult completed() {
        return new CompleteResult("checkout-1", "completed", new OrderRef("order-1", null),
                new PaymentRef("payment-1", "SUCCESS", null));
    }

    @Test
    void completedApprovalWithoutCredential_recoversAwaitingCheckout() {
        MerchantCheckoutRepository repository = mock(MerchantCheckoutRepository.class);
        PaymentApprovalClient approvals = mock(PaymentApprovalClient.class);
        when(repository.findCompleting(NOW)).thenReturn(List.of());
        when(repository.findAwaitingApproval()).thenReturn(List.of(row));
        when(repository.beginCompleting(1L, NOW)).thenReturn(true);
        when(approvals.status("approval-1")).thenReturn(new PaymentApprovalStatusResponse(
                "approval-1", ApprovalStatus.COMPLETED, "payment-1", PaymentStatus.SUCCESS,
                19600, "KRW", null, null, "카드", null,
                List.of(new PaymentApprovalItem("merchant-a", "checkout-1", null, null,
                        "payment-1", PaymentStatus.SUCCESS, "카드"))));
        when(merchant.current("checkout-1")).thenReturn(completed());
        new PaymentApprovalPollingWorker(repository, approvals, completion, Clock.fixed(NOW, ZoneOffset.UTC)).poll();
        verify(repository).markCompleted(1L, NOW);
        verify(merchant, never()).complete(any(), any(), any());
    }

    private PaymentApprovalStatusResponse status() {
        return new PaymentApprovalStatusResponse("approval-1", ApprovalStatus.COMPLETED, "payment-1",
                PaymentStatus.SUCCESS, 19600L, "KRW", "credential-1", NOW.minusSeconds(30), "카드", null,
                List.of(new PaymentApprovalItem("merchant-a", "checkout-1", "credential-1",
                        NOW.minusSeconds(30), "payment-1", PaymentStatus.SUCCESS, "카드")));
    }
}
