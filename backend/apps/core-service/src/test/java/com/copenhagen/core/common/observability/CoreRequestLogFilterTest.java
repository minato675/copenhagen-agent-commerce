package com.copenhagen.core.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CoreRequestLogFilterTest {

    private final CoreRequestLogFilter filter = new CoreRequestLogFilter();

    @AfterEach
    void clearContext() {
        CoreLogContext.clear();
    }

    @Test
    void requestIdIsGeneratedReturnedAndClearedAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdSeenByChain = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> requestIdSeenByChain.set(
                MDC.get(CoreLogContext.REQUEST_ID));

        filter.doFilter(request, response, chain);

        assertNotNull(response.getHeader(CoreRequestLogFilter.REQUEST_ID_HEADER));
        assertEquals(response.getHeader(CoreRequestLogFilter.REQUEST_ID_HEADER), requestIdSeenByChain.get());
        assertNull(MDC.get(CoreLogContext.REQUEST_ID));
    }

    @Test
    void validRequestIdAndClientMetadataArePropagated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/core/v1/auth/login");
        request.addHeader(CoreRequestLogFilter.REQUEST_ID_HEADER, "request-123");
        request.addHeader("X-App-Version", "1.2.3");
        request.addHeader("X-OS-Version", "Android 15");
        request.addHeader("X-Platform", "ANDROID");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertEquals("request-123", CoreLogContext.get(CoreLogContext.REQUEST_ID));
            assertEquals("1.2.3", CoreLogContext.get(CoreLogContext.APP_VERSION));
            assertEquals("Android 15", CoreLogContext.get(CoreLogContext.OS_VERSION));
            assertEquals("ANDROID", CoreLogContext.get(CoreLogContext.PLATFORM));
        });

        assertEquals("request-123", response.getHeader(CoreRequestLogFilter.REQUEST_ID_HEADER));
        assertNull(CoreLogContext.get(CoreLogContext.REQUEST_ID));
    }
}
