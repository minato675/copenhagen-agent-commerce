package com.copenhagen.pay.common.observability;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Pay API 요청의 시작·종료와 공통 요청 ID를 기록합니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PayRequestLogFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String APP_VERSION_HEADER = "X-App-Version";
    private static final String OS_VERSION_HEADER = "X-OS-Version";
    private static final String PLATFORM_HEADER = "X-Platform";
    private static final int MAX_REQUEST_ID_LENGTH = 100;
    private static final int MAX_CLIENT_METADATA_LENGTH = 50;
    private static final Logger log = LoggerFactory.getLogger(PayRequestLogFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !DispatcherType.REQUEST.equals(request.getDispatcherType());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        long startedAt = System.nanoTime();
        String path = PayLogContext.safePath(request.getRequestURI());
        PayLogContext.put(PayLogContext.REQUEST_ID, requestId);
        PayLogContext.put(
                PayLogContext.APP_VERSION,
                resolveClientMetadata(request.getHeader(APP_VERSION_HEADER)));
        PayLogContext.put(
                PayLogContext.OS_VERSION,
                resolveClientMetadata(request.getHeader(OS_VERSION_HEADER)));
        PayLogContext.put(
                PayLogContext.PLATFORM,
                resolveClientMetadata(request.getHeader(PLATFORM_HEADER)));
        response.setHeader(REQUEST_ID_HEADER, requestId);
        log.info(
                "pay.request.start service=pay-service method={} path={}",
                request.getMethod(),
                path);

        try {
            filterChain.doFilter(request, response);
        } finally {
            PayPrincipal principal = PayRequestContext.findPrincipal(request).orElse(null);
            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
            log.info(
                    "pay.request.complete service=pay-service method={} path={} status={} "
                            + "durationMs={} principalType={} authenticated={}",
                    request.getMethod(),
                    path,
                    response.getStatus(),
                    durationMillis,
                    principal == null ? "ANONYMOUS" : principal.type(),
                    principal != null);
            PayLogContext.clear();
        }
    }

    static String resolveRequestId(String header) {
        if (header == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REQUEST_ID_LENGTH || containsControlCharacter(trimmed)) {
            return UUID.randomUUID().toString();
        }
        return trimmed;
    }

    private String resolveClientMetadata(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_CLIENT_METADATA_LENGTH || containsControlCharacter(trimmed)) {
            return "INVALID";
        }
        return trimmed;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
