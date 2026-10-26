package com.copenhagen.pay.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class MerchantPaymentQueryControllerTest {

    private static final String PAYMENT_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void returnsOnlyThePublicPaymentStatusContractWithoutIdempotencyKey() {
        MerchantPaymentQueryService service = org.mockito.Mockito.mock(
                MerchantPaymentQueryService.class);
        MerchantPaymentQueryController controller = new MerchantPaymentQueryController(service);
        MerchantPaymentQueryRepository.PaymentView payment = new MerchantPaymentQueryRepository.PaymentView(
                PAYMENT_ID,
                PaymentStatus.UNKNOWN,
                87000,
                "KRW");
        when(service.find("merchant-a", PAYMENT_ID)).thenReturn(payment);

        ResponseEntity<MerchantPaymentQueryController.PaymentStatusResponse> response =
                controller.find(PAYMENT_ID, authenticatedRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(
                new MerchantPaymentQueryController.PaymentStatusResponse(
                        PAYMENT_ID,
                        "UNKNOWN",
                        87000,
                        "KRW",
                        "WAIT_FOR_PAYMENT_STATUS"),
                response.getBody());
        verify(service).find("merchant-a", PAYMENT_ID);
    }

    @Test
    void mapsReadyAndProcessingToWaitForPaymentStatus() {
        MerchantPaymentQueryService service = org.mockito.Mockito.mock(
                MerchantPaymentQueryService.class);
        MerchantPaymentQueryController controller = new MerchantPaymentQueryController(service);

        for (PaymentStatus status : new PaymentStatus[] {
            PaymentStatus.READY,
            PaymentStatus.PROCESSING
        }) {
            when(service.find(eq("merchant-a"), eq(PAYMENT_ID)))
                    .thenReturn(new MerchantPaymentQueryRepository.PaymentView(
                            PAYMENT_ID,
                            status,
                            87000,
                            "KRW"));

            MerchantPaymentQueryController.PaymentStatusResponse response = controller
                    .find(PAYMENT_ID, authenticatedRequest())
                    .getBody();

            assertEquals(status.name(), response.status());
            assertEquals("WAIT_FOR_PAYMENT_STATUS", response.nextAction());
        }
    }

    @Test
    void omitsNoContractFieldsAndDoesNotRequireIdempotencyKeyForSuccess() {
        MerchantPaymentQueryService service = org.mockito.Mockito.mock(
                MerchantPaymentQueryService.class);
        MerchantPaymentQueryController controller = new MerchantPaymentQueryController(service);
        when(service.find("merchant-a", PAYMENT_ID)).thenReturn(
                new MerchantPaymentQueryRepository.PaymentView(
                        PAYMENT_ID,
                        PaymentStatus.SUCCESS,
                        87000,
                        "KRW"));

        MerchantPaymentQueryController.PaymentStatusResponse response = controller
                .find(PAYMENT_ID, authenticatedRequest())
                .getBody();

        assertEquals(PAYMENT_ID, response.paymentId());
        assertEquals("SUCCESS", response.status());
        assertEquals(87000, response.amountMinor());
        assertEquals("KRW", response.currency());
        assertEquals(null, response.nextAction());
        verify(service).find("merchant-a", PAYMENT_ID);
    }

    @Test
    void rejectsNonMerchantPrincipalBeforeQuerying() {
        MerchantPaymentQueryService service = org.mockito.Mockito.mock(
                MerchantPaymentQueryService.class);
        MerchantPaymentQueryController controller = new MerchantPaymentQueryController(service);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/merchant/v1/payments/" + PAYMENT_ID);
        PayRequestContext.setPrincipal(
                request,
                new PayPrincipal(PayPrincipal.Type.USER, "user-001"));

        PayException exception = assertThrows(
                PayException.class,
                () -> controller.find(PAYMENT_ID, request));

        assertEquals(PayErrorCode.FORBIDDEN, exception.errorCode());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsCanonicalUuidPaymentIds() {
        MerchantPaymentQueryService service = org.mockito.Mockito.mock(
                MerchantPaymentQueryService.class);
        MerchantPaymentQueryController controller = new MerchantPaymentQueryController(service);
        when(service.find("merchant-a", PAYMENT_ID)).thenReturn(
                new MerchantPaymentQueryRepository.PaymentView(
                        PAYMENT_ID,
                        PaymentStatus.FAILED,
                        87000,
                        "KRW"));

        controller.find(UUID.fromString(PAYMENT_ID).toString(), authenticatedRequest());

        verify(service).find("merchant-a", PAYMENT_ID);
    }

    private HttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/merchant/v1/payments/" + PAYMENT_ID);
        PayRequestContext.setPrincipal(
                request,
                new PayPrincipal(
                        PayPrincipal.Type.SERVICE,
                        "merchant-a",
                        PayPrincipal.ServiceType.MERCHANT_SERVICE));
        return request;
    }
}
