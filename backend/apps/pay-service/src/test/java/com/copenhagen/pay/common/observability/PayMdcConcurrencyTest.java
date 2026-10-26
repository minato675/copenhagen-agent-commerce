package com.copenhagen.pay.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.ServletException;
import java.util.concurrent.Callable;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PayMdcConcurrencyTest {

    private final PayRequestLogFilter filter = new PayRequestLogFilter();

    @AfterEach
    void clearContext() {
        PayLogContext.clear();
    }

    @Test
    void concurrentRequestsDoNotMixTraceOrClientMetadataAndAlwaysCleanUp() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RequestObservation> first = executor.submit(
                    request("request-a", "1.0.0", "Android 14", "ANDROID", barrier));
            Future<RequestObservation> second = executor.submit(
                    request("request-b", "2.0.0", "iOS 18", "IOS", barrier));

            assertEquals(new RequestObservation("request-a", "request-a", "1.0.0"), first.get());
            assertEquals(new RequestObservation("request-b", "request-b", "2.0.0"), second.get());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Callable<RequestObservation> request(
            String requestId,
            String appVersion,
            String osVersion,
            String platform,
            CyclicBarrier barrier) {
        return () -> {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/pay/v1/payments");
            request.addHeader(PayRequestLogFilter.REQUEST_ID_HEADER, requestId);
            request.addHeader("X-App-Version", appVersion);
            request.addHeader("X-OS-Version", osVersion);
            request.addHeader("X-Platform", platform);
            MockHttpServletResponse response = new MockHttpServletResponse();

            final String[] observed = new String[2];
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException exception) {
                    Thread.currentThread().interrupt();
                    throw new ServletException("concurrent request barrier was interrupted", exception);
                }
                observed[0] = PayLogContext.get(PayLogContext.REQUEST_ID);
                observed[1] = PayLogContext.get(PayLogContext.APP_VERSION);
                assertEquals(osVersion, PayLogContext.get(PayLogContext.OS_VERSION));
                assertEquals(platform, PayLogContext.get(PayLogContext.PLATFORM));
                assertEquals(requestId, observed[0]);
            });

            assertNull(PayLogContext.get(PayLogContext.REQUEST_ID));
            assertNull(PayLogContext.get(PayLogContext.APP_VERSION));
            assertEquals(requestId, response.getHeader(PayRequestLogFilter.REQUEST_ID_HEADER));
            return new RequestObservation(requestId, observed[0], observed[1]);
        };
    }

    private record RequestObservation(
            String responseRequestId,
            String contextRequestId,
            String appVersion) {
    }
}
