package com.copenhagen.pay.payment.credential.pin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class PaymentPinControllerTest {

    @Test
    void statusUsesAuthenticatedSubjectAndReturnsResponse() {
        PaymentPinService service = org.mockito.Mockito.mock(PaymentPinService.class);
        PaymentPinStatusResponse expected = new PaymentPinStatusResponse(
                false,
                PaymentPinStatus.NOT_REGISTERED,
                "REGISTER_PAYMENT_PIN");
        when(service.status("user-001")).thenReturn(expected);
        PaymentPinController controller = new PaymentPinController(service);
        HttpServletRequest request = authenticatedRequest("user-001");

        ResponseEntity<PaymentPinStatusResponse> response = controller.status(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
        verify(service).status("user-001");
    }

    private HttpServletRequest authenticatedRequest(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/pay/v1/users/me/payment-pin");
        PayRequestContext.setPrincipal(
                request,
                new PayPrincipal(PayPrincipal.Type.USER, userId));
        return request;
    }
}
