package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.copenhagen.contract.internalapi.ApprovalStatus;
import com.copenhagen.contract.internalapi.PaymentApprovalNextAction;
import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class PaymentApprovalStatusControllerTest {

    private static final String APPROVAL_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void returnsSharedStatusContractForCoreService() {
        PaymentApprovalStatusService service =
                org.mockito.Mockito.mock(PaymentApprovalStatusService.class);
        PaymentApprovalStatusController controller =
                new PaymentApprovalStatusController(service);
        PaymentApprovalStatusResponse expected = new PaymentApprovalStatusResponse(
                APPROVAL_ID,
                ApprovalStatus.APPROVED,
                "22222222-2222-2222-2222-222222222222",
                PaymentStatus.UNKNOWN,
                87000,
                "KRW",
                "33333333-3333-3333-3333-333333333333",
                java.time.Instant.parse("2026-09-18T00:05:00Z"),
                "VISA **** 1234",
                PaymentApprovalNextAction.WAIT_FOR_PAYMENT_STATUS);
        when(service.find(APPROVAL_ID)).thenReturn(expected);

        ResponseEntity<PaymentApprovalStatusResponse> response = controller.find(
                APPROVAL_ID,
                authenticatedCoreRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
        verify(service).find(APPROVAL_ID);
    }

    @Test
    void rejectsUserPrincipalBeforeQuerying() {
        PaymentApprovalStatusService service =
                org.mockito.Mockito.mock(PaymentApprovalStatusService.class);
        PaymentApprovalStatusController controller =
                new PaymentApprovalStatusController(service);
        MockHttpServletRequest request = request();
        PayRequestContext.setPrincipal(request, new PayPrincipal(
                PayPrincipal.Type.USER,
                "user-001"));

        PayException exception = assertThrows(
                PayException.class,
                () -> controller.find(APPROVAL_ID, request));

        assertEquals(PayErrorCode.FORBIDDEN, exception.errorCode());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMerchantPrincipalBeforeQuerying() {
        PaymentApprovalStatusService service =
                org.mockito.Mockito.mock(PaymentApprovalStatusService.class);
        PaymentApprovalStatusController controller =
                new PaymentApprovalStatusController(service);
        MockHttpServletRequest request = request();
        PayRequestContext.setPrincipal(request, new PayPrincipal(
                PayPrincipal.Type.SERVICE,
                "merchant-a",
                PayPrincipal.ServiceType.MERCHANT_SERVICE));

        PayException exception = assertThrows(
                PayException.class,
                () -> controller.find(APPROVAL_ID, request));

        assertEquals(PayErrorCode.FORBIDDEN, exception.errorCode());
        verifyNoInteractions(service);
    }

    @Test
    void requiresAuthenticationWhenPrincipalIsMissing() {
        PaymentApprovalStatusService service =
                org.mockito.Mockito.mock(PaymentApprovalStatusService.class);
        PaymentApprovalStatusController controller =
                new PaymentApprovalStatusController(service);

        PayException exception = assertThrows(
                PayException.class,
                () -> controller.find(APPROVAL_ID, request()));

        assertEquals(PayErrorCode.AUTHENTICATION_REQUIRED, exception.errorCode());
        verifyNoInteractions(service);
    }

    private HttpServletRequest authenticatedCoreRequest() {
        MockHttpServletRequest request = request();
        PayRequestContext.setPrincipal(request, new PayPrincipal(
                PayPrincipal.Type.SERVICE,
                "core-service",
                PayPrincipal.ServiceType.CORE_SERVICE));
        return request;
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest(
                "GET",
                "/internal/v1/payment-approvals/" + APPROVAL_ID + "/status");
    }
}
