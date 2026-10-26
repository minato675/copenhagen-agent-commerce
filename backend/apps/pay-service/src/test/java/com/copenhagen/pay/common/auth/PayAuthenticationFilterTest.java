package com.copenhagen.pay.common.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.web.PayRequestContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class PayAuthenticationFilterTest {

    private final ApiErrorWriter errorWriter = new ApiErrorWriter(new ObjectMapper());

    @Test
    void userTokenIsAcceptedOnAppApi() throws Exception {
        PayAuthenticationFilter filter = filterWith(Map.of("user-token", "user-001"), Map.of());
        MockHttpServletRequest request = request("GET", "/api/pay/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        PayPrincipal principal = PayRequestContext.requirePrincipal(request);
        assertEquals(PayPrincipal.Type.USER, principal.type());
        assertEquals("user-001", principal.subject());
    }

    @Test
    void serviceTokenIsAcceptedOnInternalApi() throws Exception {
        PayAuthenticationFilter filter = filterWith(Map.of(), Map.of("core-token", "core-service"));
        MockHttpServletRequest request = request("POST", "/internal/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer core-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals(
                PayPrincipal.Type.SERVICE,
                PayRequestContext.requirePrincipal(request).type());
        assertEquals(
                PayPrincipal.ServiceType.CORE_SERVICE,
                PayRequestContext.requirePrincipal(request).serviceType());
    }

    @Test
    void merchantTokenIsAcceptedOnMerchantApiWhenMerchantIsRegistered() throws Exception {
        PayAuthenticationFilter filter = filterWith(
                Map.of(),
                Map.of(),
                Map.of("merchant-token", "merchant-a"));
        MockHttpServletRequest request = request("POST", "/merchant/v1/payments");
        request.addHeader("Authorization", "Bearer merchant-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        PayPrincipal principal = PayRequestContext.requirePrincipal(request);
        assertEquals(PayPrincipal.Type.SERVICE, principal.type());
        assertEquals(PayPrincipal.ServiceType.MERCHANT_SERVICE, principal.serviceType());
        assertEquals("merchant-a", principal.subject());
    }

    @Test
    void missingTokenReturnsUnauthorizedError() throws Exception {
        PayAuthenticationFilter filter = filterWith(Map.of(), Map.of());
        MockHttpServletRequest request = request("GET", "/api/pay/v1/payment-approvals");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertNotNull(response.getContentAsString());
        assertEquals(true, response.getContentAsString().contains("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void userTokenCannotCallInternalApi() throws Exception {
        PayAuthenticationFilter filter = filterWith(Map.of("user-token", "user-001"), Map.of());
        MockHttpServletRequest request = request("POST", "/internal/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertInstanceOf(String.class, response.getContentAsString());
        assertEquals(true, response.getContentAsString().contains("FORBIDDEN"));
    }

    @Test
    void userTokenCannotCallMerchantApi() throws Exception {
        PayAuthenticationFilter filter = filterWith(
                Map.of("user-token", "user-001"),
                Map.of(),
                Map.of("merchant-token", "merchant-a"));
        MockHttpServletRequest request = request("POST", "/merchant/v1/payments");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
    }

    @Test
    void merchantTokenCannotCallInternalApi() throws Exception {
        PayAuthenticationFilter filter = filterWith(
                Map.of(),
                Map.of(),
                Map.of("merchant-token", "merchant-a"));
        MockHttpServletRequest request = request("GET", "/internal/v1/payment-approvals/approval-001/status");
        request.addHeader("Authorization", "Bearer merchant-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
    }

    @Test
    void coreServiceTokenCannotCallMerchantApi() throws Exception {
        PayAuthenticationFilter filter = filterWith(
                Map.of(),
                Map.of("core-token", "core-service"),
                Map.of("merchant-token", "merchant-a"));
        MockHttpServletRequest request = request("POST", "/merchant/v1/payments");
        request.addHeader("Authorization", "Bearer core-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
    }

    @Test
    void unregisteredMerchantTokenIsForbidden() throws Exception {
        PayAuthProperties properties = new PayAuthProperties(
                Map.of(),
                Map.of(),
                Map.of("merchant-token", "merchant-a"));
        PayAuthenticationFilter filter = new PayAuthenticationFilter(
                new ConfiguredBearerTokenVerifier(properties),
                errorWriter,
                PayRouteAuthorizer.withMerchantRegistration("merchant-active"::equals));
        MockHttpServletRequest request = request("POST", "/merchant/v1/payments");
        request.addHeader("Authorization", "Bearer merchant-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
    }

    @Test
    void invalidTokenReturnsUnauthorizedWithoutEchoingToken() throws Exception {
        String invalidToken = "invalid-token";
        PayAuthenticationFilter filter = filterWith(Map.of(), Map.of());
        MockHttpServletRequest request = request("GET", "/api/pay/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer " + invalidToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_TOKEN"));
        assertTrue(!response.getContentAsString().contains(invalidToken));
    }

    @Test
    void healthEndpointKeepsExistingUnauthenticatedPolicy() throws Exception {
        PayAuthenticationFilter filter = filterWith(Map.of(), Map.of());
        MockHttpServletRequest request = request("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertTrue(PayRequestContext.findPrincipal(request).isEmpty());
    }

    @Test
    void merchantPrincipalMustMatchRequestedMerchantId() {
        MockHttpServletRequest request = request("POST", "/merchant/v1/payments");
        PayRequestContext.setPrincipal(
                request,
                new PayPrincipal(
                        PayPrincipal.Type.SERVICE,
                        "merchant-a",
                        PayPrincipal.ServiceType.MERCHANT_SERVICE));

        assertDoesNotThrow(
                () -> PayRequestContext.requireMerchantPrincipal(request, "merchant-a"));
        PayException exception = assertThrows(
                PayException.class,
                () -> PayRequestContext.requireMerchantPrincipal(request, "merchant-b"));

        assertEquals(PayErrorCode.FORBIDDEN, exception.errorCode());
    }

    private PayAuthenticationFilter filterWith(
            Map<String, String> userTokens,
            Map<String, String> serviceTokens) {
        PayAuthProperties properties = new PayAuthProperties(userTokens, serviceTokens);
        return new PayAuthenticationFilter(
                new ConfiguredBearerTokenVerifier(properties),
                errorWriter);
    }

    private PayAuthenticationFilter filterWith(
            Map<String, String> userTokens,
            Map<String, String> serviceTokens,
            Map<String, String> merchantTokens) {
        PayAuthProperties properties = new PayAuthProperties(
                userTokens,
                serviceTokens,
                merchantTokens);
        return new PayAuthenticationFilter(
                new ConfiguredBearerTokenVerifier(properties),
                errorWriter,
                PayRouteAuthorizer.withMerchantRegistration(merchantTokens::containsValue));
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
