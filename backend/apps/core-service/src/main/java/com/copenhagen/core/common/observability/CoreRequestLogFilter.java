package com.copenhagen.core.common.observability;

import com.copenhagen.core.common.auth.CoreRequestContext;
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

/** Core API 요청의 시작·종료와 Pay로 이어질 공통 요청 ID를 기록합니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CoreRequestLogFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String APP_VERSION_HEADER = "X-App-Version";
    private static final String OS_VERSION_HEADER = "X-OS-Version";
    private static final String PLATFORM_HEADER = "X-Platform";
    private static final int MAX_REQUEST_ID_LENGTH = 100;
    private static final int MAX_CLIENT_METADATA_LENGTH = 50;
    private static final Logger log = LoggerFactory.getLogger(CoreRequestLogFilter.class);

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
        CoreLogContext.put(CoreLogContext.REQUEST_ID, requestId);
        CoreLogContext.put(
                CoreLogContext.APP_VERSION,
                resolveClientMetadata(request.getHeader(APP_VERSION_HEADER)));
        CoreLogContext.put(
                CoreLogContext.OS_VERSION,
                resolveClientMetadata(request.getHeader(OS_VERSION_HEADER)));
        CoreLogContext.put(
                CoreLogContext.PLATFORM,
                resolveClientMetadata(request.getHeader(PLATFORM_HEADER)));
        response.setHeader(REQUEST_ID_HEADER, requestId);
        log.info("core.request.start method={} path={}", request.getMethod(), request.getRequestURI());

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
            log.info(
                    "core.request.complete method={} path={} status={} durationMs={} authenticated={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMillis,
                    CoreRequestContext.findPrincipal(request).isPresent());
            CoreLogContext.clear();
        }
    }

    static String resolveRequestId(String header) {
        if (header == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REQUEST_ID_LENGTH
                || containsControlCharacter(trimmed)) {
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
