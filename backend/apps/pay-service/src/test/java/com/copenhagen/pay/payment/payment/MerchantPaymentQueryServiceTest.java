package com.copenhagen.pay.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class MerchantPaymentQueryServiceTest {

    private static final String PAYMENT_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void findsPaymentOnlyWithTheAuthenticatedMerchantScope() {
        MerchantPaymentQueryRepository repository = org.mockito.Mockito.mock(
                MerchantPaymentQueryRepository.class);
        MerchantPaymentQueryService service = new MerchantPaymentQueryService(repository);
        MerchantPaymentQueryRepository.PaymentView payment = new MerchantPaymentQueryRepository.PaymentView(
                PAYMENT_ID,
                PaymentStatus.UNKNOWN,
                87000,
                "KRW");
        when(repository.findByMerchantIdAndPaymentId("merchant-a", PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        assertEquals(payment, service.find("merchant-a", PAYMENT_ID));
        verify(repository).findByMerchantIdAndPaymentId("merchant-a", PAYMENT_ID);
    }

    @Test
    void normalizesUppercaseUuidBeforeQuerying() {
        MerchantPaymentQueryRepository repository = org.mockito.Mockito.mock(
                MerchantPaymentQueryRepository.class);
        MerchantPaymentQueryService service = new MerchantPaymentQueryService(repository);
        String uppercasePaymentId = PAYMENT_ID.toUpperCase(java.util.Locale.ROOT);
        MerchantPaymentQueryRepository.PaymentView payment = new MerchantPaymentQueryRepository.PaymentView(
                PAYMENT_ID,
                PaymentStatus.SUCCESS,
                87000,
                "KRW");
        when(repository.findByMerchantIdAndPaymentId("merchant-a", PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        assertEquals(payment, service.find("merchant-a", uppercasePaymentId));
        verify(repository).findByMerchantIdAndPaymentId("merchant-a", PAYMENT_ID);
    }

    @Test
    void rejectsMalformedPaymentIdBeforeDatabaseAccess() {
        MerchantPaymentQueryRepository repository = org.mockito.Mockito.mock(
                MerchantPaymentQueryRepository.class);
        MerchantPaymentQueryService service = new MerchantPaymentQueryService(repository);

        PayException exception = assertThrows(
                PayException.class,
                () -> service.find("merchant-a", "payment-001"));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
        verifyNoInteractions(repository);
    }

    @Test
    void returnsNotFoundWithoutRevealingAnotherMerchantPayment() {
        MerchantPaymentQueryRepository repository = org.mockito.Mockito.mock(
                MerchantPaymentQueryRepository.class);
        MerchantPaymentQueryService service = new MerchantPaymentQueryService(repository);
        when(repository.findByMerchantIdAndPaymentId("merchant-a", PAYMENT_ID))
                .thenReturn(Optional.empty());

        PayException exception = assertThrows(
                PayException.class,
                () -> service.find("merchant-a", PAYMENT_ID));

        assertEquals(PayErrorCode.RESOURCE_NOT_FOUND, exception.errorCode());
    }

    @Test
    void letsDatabaseFailureReachTheApiAsAnInternalError() {
        MerchantPaymentQueryRepository repository = org.mockito.Mockito.mock(
                MerchantPaymentQueryRepository.class);
        MerchantPaymentQueryService service = new MerchantPaymentQueryService(repository);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                "database unavailable");
        when(repository.findByMerchantIdAndPaymentId("merchant-a", PAYMENT_ID))
                .thenThrow(failure);

        assertEquals(
                failure,
                assertThrows(
                        DataAccessResourceFailureException.class,
                        () -> service.find("merchant-a", PAYMENT_ID)));
    }
}
