package com.copenhagen.pay.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PayRequestLogFilterTest {

    private final PayRequestLogFilter filter = new PayRequestLogFilter();

    @AfterEach
    void clearContext() {
        PayLogContext.clear();
    }

    @Test
    void requestIdIsGeneratedReturnedAndClearedAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdSeenByChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> requestIdSeenByChain.set(
                MDC.get(PayLogContext.REQUEST_ID));

        filter.doFilter(request, response, chain);

        assertNotNull(response.getHeader(PayRequestLogFilter.REQUEST_ID_HEADER));
        assertEquals(response.getHeader(PayRequestLogFilter.REQUEST_ID_HEADER), requestIdSeenByChain.get());
        assertNull(MDC.get(PayLogContext.REQUEST_ID));
    }

    @Test
    void validRequestIdIsPropagatedAndPrincipalIsVisibleToCompletionLog() throws Exception {
        String requestId = "request-123";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/pay/v1/payments");
        request.addHeader(PayRequestLogFilter.REQUEST_ID_HEADER, requestId);
        request.addHeader("X-App-Version", "1.2.3");
        request.addHeader("X-OS-Version", "Android 15");
        request.addHeader("X-Platform", "ANDROID");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> userIdSeenByChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> {
            PayRequestContext.setPrincipal(
                    (MockHttpServletRequest) servletRequest,
                    new PayPrincipal(PayPrincipal.Type.USER, "user-1"));
            userIdSeenByChain.set(PayLogContext.get(PayLogContext.REQUEST_ID));
            assertEquals("1.2.3", PayLogContext.get(PayLogContext.APP_VERSION));
            assertEquals("Android 15", PayLogContext.get(PayLogContext.OS_VERSION));
            assertEquals("ANDROID", PayLogContext.get(PayLogContext.PLATFORM));
        };

        filter.doFilter(request, response, chain);

        assertEquals(requestId, response.getHeader(PayRequestLogFilter.REQUEST_ID_HEADER));
        assertEquals(requestId, userIdSeenByChain.get());
        assertNull(PayLogContext.get(PayLogContext.REQUEST_ID));
    }

    @Test
    void invalidRequestIdIsReplacedBeforeItIsReturned() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader(PayRequestLogFilter.REQUEST_ID_HEADER, " ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertNotNull(response.getHeader(PayRequestLogFilter.REQUEST_ID_HEADER));
        assertEquals(36, response.getHeader(PayRequestLogFilter.REQUEST_ID_HEADER).length());
    }

    @Test
    void unsafeRequestPathIsRedactedFromRequestLogs() throws Exception {
        String sensitivePath = "/api/pay/" + "synthetic-secret-segment-".repeat(20);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", sensitivePath);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (PayLogCapture logs = new PayLogCapture()) {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            });

            assertTrue(logs.allText().contains("path=INVALID"));
            assertFalse(logs.allText().contains("synthetic-secret-segment"));
        }
    }
}
