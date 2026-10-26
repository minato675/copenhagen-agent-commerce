package com.copenhagen.pay.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.web.PayRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class PayIdempotencyFilterTest {

    private static final String UUID_KEY = "550e8400-e29b-41d4-a716-446655440001";

    private final PayIdempotencyFilter filter = new PayIdempotencyFilter(
            new IdempotencyKeyResolver(),
            new ApiErrorWriter(new ObjectMapper()));

    @ParameterizedTest
    @CsvSource({
        "POST, /api/pay/v1/payment-approvals/approval-001/approve",
        "POST, /api/pay/v1/payment-approvals/approval-001/reject",
        "PATCH, /api/pay/v1/payment-approvals/approval-001/payment-method",
        "POST, /api/pay/v1/users/me/biometric-credentials",
        "DELETE, /api/pay/v1/users/me/biometric-credentials",
        "POST, /api/pay/v1/users/me/payment-pin",
        "PUT, /api/pay/v1/users/me/payment-pin",
        "POST, /api/pay/v1/payment-methods/cards",
        "PATCH, /api/pay/v1/payment-methods/cards/method-001/default",
        "DELETE, /api/pay/v1/payment-methods/cards/method-001",
        "POST, /internal/v1/payment-approvals",
        "POST, /internal/v1/purchase-notifications",
        "POST, /internal/v1/purchase-notifications/00000000-0000-0000-0000-000000000001/resend",
        "POST, /merchant/v1/payments"
    })
    void stateChangingProtectedRequestRequiresKey(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method,
                path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void keyIsStoredOnRequestContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/pay/v1/payment-approvals");
        request.addHeader("Idempotency-Key", UUID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals(
                UUID_KEY,
                PayRequestContext.findIdempotencyKey(request).orElseThrow().value());
    }

    @Test
    void prefixedKeyIsRejectedForStateChangingRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/pay/v1/payment-methods/cards");
        request.addHeader(
                "Idempotency-Key",
                "register-card-550e8400-e29b-41d4-a716-446655440001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("IDEMPOTENCY_KEY_INVALID"));
    }

    @Test
    void notificationInstallationRequestDoesNotRequireIdempotencyKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT",
                "/api/pay/v1/notification-installations");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void notificationInstallationDeleteDoesNotRequireIdempotencyKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "DELETE",
                "/api/pay/v1/notification-installations/fid-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}
