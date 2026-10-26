package com.copenhagen.core.purchase;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.order.OrderValidationService;
import com.copenhagen.core.payment.PaymentApprovalClient;
import com.copenhagen.core.purchase.PurchaseWorkflowRepository.Row;
import com.copenhagen.core.purchase.PurchaseWorkflowService.CheckoutView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseWorkflowServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private final PurchaseWorkflowRepository repository = mock(PurchaseWorkflowRepository.class);
    private final OrderValidationService validation = mock(OrderValidationService.class);
    private final PaymentApprovalClient pay = mock(PaymentApprovalClient.class);
    private final PurchaseWorkflowService service = new PurchaseWorkflowService(repository, validation, pay,
            mock(CartMerchants.class), Clock.fixed(NOW, ZoneOffset.UTC));
    private final Row row = new Row(1L, UUID.randomUUID(), 2L, UUID.randomUUID(), 3L, UUID.randomUUID(),
            5, 4L, "PENDING_ADDRESS", null, NOW.plusSeconds(600), null, null);

    @Test
    void missingAddressSendsHandoffWithoutCreatingAnotherPurchaseOrApproval() {
        when(repository.claim(1L, NOW)).thenReturn(true);
        when(repository.require(row.publicId(), 2L)).thenReturn(row);
        service.process(row);
        verify(pay).notifyPurchase(row.userPublicId(), row.publicId());
        verify(validation, never()).validateExisting(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(repository).finish(1L, "PENDING_ADDRESS", null, NOW);
    }

    @Test
    void registeredAddressIsNotChosenForTheUser() {
        // 기본 배송지가 있어도 워커는 주소를 대신 고르지 않는다. 검증으로 넘어가는 유일한 경로는 resume이다.
        when(repository.claim(1L, NOW)).thenReturn(true);
        when(repository.require(row.publicId(), 2L)).thenReturn(row);
        service.process(row);
        verify(repository, never()).resume(any(), org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(validation, never()).validateExisting(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(pay).notifyPurchase(row.userPublicId(), row.publicId());
        verify(repository).finish(1L, "PENDING_ADDRESS", null, NOW);
    }

    @Test
    void addressSelectedByUserContinuesToValidationAndApproval() {
        Row selected = new Row(row.id(), row.publicId(), row.userId(), row.userPublicId(), row.cartId(),
                row.cartPublicId(), row.cartVersion(), row.connectionId(), "CREATING_CHECKOUTS",
                UUID.randomUUID(), row.expiresAt(), null, null);
        when(repository.claim(1L, NOW)).thenReturn(true);
        when(repository.require(selected.publicId(), 2L)).thenReturn(selected);
        service.process(selected);
        verify(validation).validateExisting(any(), eq(selected.cartPublicId()), eq(selected.cartVersion()),
                eq(selected.addressId()));
        verify(pay, never()).notifyPurchase(any(), any());
        verify(repository).finish(1L, "AWAITING_APPROVAL", null, NOW);
    }

    @Test
    void differentAgentConnectionCannotReadPurchaseOrTriggerNotification() {
        when(repository.require(row.publicId(), 2L)).thenReturn(row);
        assertEquals(CoreErrorCode.ORDER_NOT_FOUND, assertThrows(CoreException.class,
                () -> service.view(row.publicId(), 2L, 9L)).errorCode());
        verify(pay, never()).notificationStatus(any(), any());
    }

    @Test
    void paidMerchantDoesNotHideRejectedOrPendingMerchants() {
        CheckoutView paid = checkout("COMPLETED");
        assertEquals("AWAITING_APPROVAL", PurchaseWorkflowService.aggregate("AWAITING_APPROVAL",
                List.of(paid, checkout("AWAITING_APPROVAL"))));
        assertEquals("PARTIALLY_COMPLETED", PurchaseWorkflowService.aggregate("AWAITING_APPROVAL",
                List.of(paid, checkout("REJECTED"))));
        assertEquals("REJECTED", PurchaseWorkflowService.aggregate("AWAITING_APPROVAL",
                List.of(checkout("REJECTED"))));
        assertEquals("EXPIRED", PurchaseWorkflowService.aggregate("AWAITING_APPROVAL",
                List.of(checkout("EXPIRED"))));
    }

    private CheckoutView checkout(String status) {
        return new CheckoutView("merchant-a", UUID.randomUUID().toString(), status, 1000, "KRW");
    }
}
