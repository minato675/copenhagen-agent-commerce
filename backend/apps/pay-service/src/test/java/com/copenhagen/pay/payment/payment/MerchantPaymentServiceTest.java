package com.copenhagen.pay.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.credential.mandate.CreatedPayment;
import com.copenhagen.pay.payment.credential.mandate.PaymentCredentialConsumptionService;
import com.copenhagen.pay.payment.merchant.MerchantAccount;
import com.copenhagen.pay.payment.merchant.MerchantAccountRepository;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MerchantPaymentServiceTest {

    @Test
    void consumesCredentialThenExecutesPaymentWithTheSameRequestIdentity() {
        MerchantAccountRepository merchantRepository = org.mockito.Mockito.mock(
                MerchantAccountRepository.class);
        PaymentCredentialConsumptionService credentialService = org.mockito.Mockito.mock(
                PaymentCredentialConsumptionService.class);
        PaymentExecutionService executionService = org.mockito.Mockito.mock(
                PaymentExecutionService.class);
        MerchantPaymentService service = new MerchantPaymentService(
                merchantRepository,
                credentialService,
                executionService);
        MerchantAccount merchant = new MerchantAccount(
                "merchant-account-001",
                "merchant-a",
                "Merchant A",
                "ACTIVE");
        CreatedPayment payment = payment();
        PaymentExecutionResult execution = new PaymentExecutionResult(
                payment.id(),
                PaymentStatus.SUCCESS,
                "attempt-001",
                "pg-tx-001",
                "receipt-001",
                "CREATE_ORDER");
        when(merchantRepository.findActiveByMerchantId("merchant-a"))
                .thenReturn(Optional.of(merchant));
        when(credentialService.consume(any())).thenReturn(payment);
        when(executionService.execute(any())).thenReturn(execution);

        MerchantPaymentService.PaymentResult result = service.create(command());

        assertEquals(payment, result.payment());
        assertEquals(execution, result.execution());
        verify(credentialService).consume(any(PaymentCredentialConsumptionService.ConsumeCommand.class));
        verify(executionService).execute(new PaymentExecutionCommand(
                payment.id(),
                "550e8400-e29b-41d4-a716-446655440200",
                "request-hash"));
    }

    @Test
    void rejectsUnregisteredMerchantBeforeCredentialLookup() {
        MerchantAccountRepository merchantRepository = org.mockito.Mockito.mock(
                MerchantAccountRepository.class);
        PaymentCredentialConsumptionService credentialService = org.mockito.Mockito.mock(
                PaymentCredentialConsumptionService.class);
        PaymentExecutionService executionService = org.mockito.Mockito.mock(
                PaymentExecutionService.class);
        MerchantPaymentService service = new MerchantPaymentService(
                merchantRepository,
                credentialService,
                executionService);
        when(merchantRepository.findActiveByMerchantId("merchant-a"))
                .thenReturn(Optional.empty());

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(command()));

        assertEquals(PayErrorCode.FORBIDDEN, exception.errorCode());
        verifyNoInteractions(credentialService, executionService);
    }

    @Test
    void enforcesUuidMerchantIdempotencyKey() {
        MerchantPaymentService service = new MerchantPaymentService(
                org.mockito.Mockito.mock(MerchantAccountRepository.class),
                org.mockito.Mockito.mock(PaymentCredentialConsumptionService.class),
                org.mockito.Mockito.mock(PaymentExecutionService.class));
        MerchantPaymentService.CreateCommand invalid = new MerchantPaymentService.CreateCommand(
                "merchant-a",
                "checkout-123",
                "sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                87000,
                "KRW",
                "credential-001",
                "not-a-uuid",
                "request-hash");

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(invalid));

        assertEquals(PayErrorCode.IDEMPOTENCY_KEY_INVALID, exception.errorCode());
    }

    private MerchantPaymentService.CreateCommand command() {
        return new MerchantPaymentService.CreateCommand(
                "merchant-a",
                "checkout-123",
                "sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                87000,
                "KRW",
                "credential-001",
                "550e8400-e29b-41d4-a716-446655440200",
                "request-hash");
    }

    private CreatedPayment payment() {
        return new CreatedPayment(
                "payment-001",
                "merchant-account-001",
                "checkout-123",
                "purchase-001",
                "credential-001",
                87000,
                "KRW",
                PaymentStatus.READY,
                "550e8400-e29b-41d4-a716-446655440200",
                "request-hash");
    }
}
