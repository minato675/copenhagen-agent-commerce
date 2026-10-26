package com.copenhagen.pay.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyKey;
import com.copenhagen.pay.common.idempotency.RequestHashGenerator;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.copenhagen.pay.payment.credential.mandate.CreatedPayment;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class MerchantPaymentControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440201";

    @Test
    void createsPaymentAndReturnsSuccessContract() {
        MerchantPaymentService service = org.mockito.Mockito.mock(MerchantPaymentService.class);
        RequestHashGenerator requestHashGenerator = org.mockito.Mockito.mock(
                RequestHashGenerator.class);
        MerchantPaymentController controller = new MerchantPaymentController(
                service,
                requestHashGenerator);
        MerchantPaymentController.PaymentRequest request = request();
        CreatedPayment payment = payment(PaymentStatus.READY);
        PaymentExecutionResult execution = new PaymentExecutionResult(
                payment.id(),
                PaymentStatus.SUCCESS,
                "attempt-001",
                "pg-tx-001",
                "receipt-001",
                "CREATE_ORDER");
        when(requestHashGenerator.generate(any(HttpServletRequest.class), any()))
                .thenReturn("request-hash");
        when(service.create(any())).thenReturn(
                new MerchantPaymentService.PaymentResult(payment, execution));
        MockHttpServletRequest httpRequest = authenticatedRequest();

        ResponseEntity<MerchantPaymentController.PaymentResponse> response = controller.create(
                request,
                "request-001",
                httpRequest);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(
                new MerchantPaymentController.PaymentResponse(
                        "payment-001",
                        "SUCCESS",
                        87000,
                        "KRW",
                        "pg-tx-001",
                        null,
                        "CREATE_ORDER"),
                response.getBody());
        verify(service).create(any(MerchantPaymentService.CreateCommand.class));
    }

    @Test
    void mapsFailedAndUnknownTo200ContractResponses() {
        MerchantPaymentService service = org.mockito.Mockito.mock(MerchantPaymentService.class);
        RequestHashGenerator requestHashGenerator = org.mockito.Mockito.mock(
                RequestHashGenerator.class);
        MerchantPaymentController controller = new MerchantPaymentController(
                service,
                requestHashGenerator);
        MerchantPaymentController.PaymentRequest request = request();
        CreatedPayment payment = payment(PaymentStatus.READY);
        when(requestHashGenerator.generate(any(HttpServletRequest.class), any()))
                .thenReturn("request-hash");
        MockHttpServletRequest httpRequest = authenticatedRequest();

        when(service.create(any())).thenReturn(new MerchantPaymentService.PaymentResult(
                payment,
                new PaymentExecutionResult(
                        payment.id(),
                        PaymentStatus.FAILED,
                        "attempt-001",
                        null,
                        null,
                        "RESTART_CHECKOUT")));
        ResponseEntity<MerchantPaymentController.PaymentResponse> failed = controller.create(
                request,
                "request-001",
                httpRequest);

        assertEquals(200, failed.getStatusCode().value());
        assertEquals("FAILED", failed.getBody().status());
        assertEquals("PG_DECLINED", failed.getBody().errorCode());
        assertEquals("SHOW_PAYMENT_FAILURE", failed.getBody().nextAction());

        when(service.create(any())).thenReturn(new MerchantPaymentService.PaymentResult(
                payment,
                new PaymentExecutionResult(
                        payment.id(),
                        PaymentStatus.UNKNOWN,
                        "attempt-001",
                        null,
                        null,
                        "WAIT_FOR_RECONCILIATION")));
        ResponseEntity<MerchantPaymentController.PaymentResponse> unknown = controller.create(
                request,
                "request-002",
                httpRequest);

        assertEquals(200, unknown.getStatusCode().value());
        assertEquals("UNKNOWN", unknown.getBody().status());
        assertEquals("WAIT_FOR_PAYMENT_STATUS", unknown.getBody().nextAction());
        assertEquals(null, unknown.getBody().errorCode());
    }

    @Test
    void rejectsMerchantPrincipalMismatchBeforeCreatingPayment() {
        MerchantPaymentService service = org.mockito.Mockito.mock(MerchantPaymentService.class);
        MerchantPaymentController controller = new MerchantPaymentController(
                service,
                org.mockito.Mockito.mock(RequestHashGenerator.class));
        MockHttpServletRequest request = authenticatedRequest();
        PayRequestContext.setPrincipal(
                request,
                new PayPrincipal(
                        PayPrincipal.Type.SERVICE,
                        "merchant-other",
                        PayPrincipal.ServiceType.MERCHANT_SERVICE));

        PayException exception = assertThrows(
                PayException.class,
                () -> controller.create(request(), "request-001", request));

        assertEquals(PayErrorCode.FORBIDDEN, exception.errorCode());
        verifyNoInteractions(service);
    }

    @Test
    void requiresRequestIdAndIdempotencyKey() {
        MerchantPaymentService service = org.mockito.Mockito.mock(MerchantPaymentService.class);
        MerchantPaymentController controller = new MerchantPaymentController(
                service,
                org.mockito.Mockito.mock(RequestHashGenerator.class));

        PayException missingRequestId = assertThrows(
                PayException.class,
                () -> controller.create(request(), " ", authenticatedRequest()));
        assertEquals(PayErrorCode.INVALID_REQUEST, missingRequestId.errorCode());

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/merchant/v1/payments");
        PayRequestContext.setPrincipal(
                request,
                new PayPrincipal(
                        PayPrincipal.Type.SERVICE,
                        "merchant-a",
                        PayPrincipal.ServiceType.MERCHANT_SERVICE));
        PayException missingIdempotency = assertThrows(
                PayException.class,
                () -> controller.create(request(), "request-001", request));
        assertEquals(PayErrorCode.IDEMPOTENCY_KEY_REQUIRED, missingIdempotency.errorCode());
        verifyNoInteractions(service);
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/merchant/v1/payments");
        PayRequestContext.setPrincipal(
                request,
                new PayPrincipal(
                        PayPrincipal.Type.SERVICE,
                        "merchant-a",
                        PayPrincipal.ServiceType.MERCHANT_SERVICE));
        PayRequestContext.setIdempotencyKey(
                request,
                new IdempotencyKey(IDEMPOTENCY_KEY));
        return request;
    }

    private MerchantPaymentController.PaymentRequest request() {
        return new MerchantPaymentController.PaymentRequest(
                "merchant-a",
                "checkout-123",
                "sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                87000,
                "KRW",
                "credential-001");
    }

    private CreatedPayment payment(PaymentStatus status) {
        return new CreatedPayment(
                "payment-001",
                "merchant-account-001",
                "checkout-123",
                "purchase-001",
                "credential-001",
                87000,
                "KRW",
                status,
                IDEMPOTENCY_KEY,
                "request-hash");
    }
}
