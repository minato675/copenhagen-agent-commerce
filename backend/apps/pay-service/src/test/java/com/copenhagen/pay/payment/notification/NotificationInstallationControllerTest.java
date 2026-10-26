package com.copenhagen.pay.payment.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class NotificationInstallationControllerTest {

    @Test
    void registersFidUsingTheAuthenticatedSubject() {
        NotificationInstallationService service = org.mockito.Mockito.mock(
                NotificationInstallationService.class);
        NotificationInstallationController controller = new NotificationInstallationController(service);
        HttpServletRequest request = authenticatedRequest("PUT", "/api/pay/v1/notification-installations");

        ResponseEntity<Void> response = controller.register(
                new NotificationInstallationController.RegisterRequest("fid-1"),
                request);

        assertEquals(204, response.getStatusCode().value());
        verify(service).register("user-1", "fid-1");
    }

    @Test
    void unregistersFidUsingTheAuthenticatedSubject() {
        NotificationInstallationService service = org.mockito.Mockito.mock(
                NotificationInstallationService.class);
        NotificationInstallationController controller = new NotificationInstallationController(service);
        HttpServletRequest request = authenticatedRequest(
                "DELETE",
                "/api/pay/v1/notification-installations/fid-1");

        ResponseEntity<Void> response = controller.unregister("fid-1", request);

        assertEquals(204, response.getStatusCode().value());
        verify(service).unregister("user-1", "fid-1");
    }

    private HttpServletRequest authenticatedRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        PayRequestContext.setPrincipal(request, new PayPrincipal(PayPrincipal.Type.USER, "user-1"));
        return request;
    }
}
