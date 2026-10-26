package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.auth.PayAuthenticationPolicy;
import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PayIdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyKeyResolver keyResolver;
    private final ApiErrorWriter errorWriter;
    private final RequestHashGenerator requestHashGenerator;
    private final PayIdempotencyService idempotencyService;

    public PayIdempotencyFilter(
            IdempotencyKeyResolver keyResolver,
            ApiErrorWriter errorWriter) {
        this(
                keyResolver,
                errorWriter,
                new RequestHashGenerator(new ObjectMapper()),
                null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PayIdempotencyFilter(
            IdempotencyKeyResolver keyResolver,
            ApiErrorWriter errorWriter,
            RequestHashGenerator requestHashGenerator,
            PayIdempotencyService idempotencyService) {
        this.keyResolver = keyResolver;
        this.errorWriter = errorWriter;
        this.requestHashGenerator = requestHashGenerator;
        this.idempotencyService = idempotencyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!request.getDispatcherType().equals(jakarta.servlet.DispatcherType.REQUEST)) {
            return true;
        }
        if (isNotificationInstallationRequest(request)) {
            return true;
        }
        if (PayAuthenticationPolicy.requiredPrincipal(request.getRequestURI())
                == PayAuthenticationPolicy.RequiredPrincipal.NONE) {
            return true;
        }
        return switch (request.getMethod()) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    private boolean isNotificationInstallationRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/pay/v1/notification-installations".equals(path)
                || path.startsWith("/api/pay/v1/notification-installations/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            IdempotencyKey idempotencyKey = keyResolver.require(request);
            PayRequestContext.setIdempotencyKey(request, idempotencyKey);
            if (idempotencyService == null) {
                filterChain.doFilter(request, response);
                return;
            }

            PayPrincipal principal = PayRequestContext.requirePrincipal(request);
            byte[] requestBody = request.getInputStream().readAllBytes();
            String requestHash = requestHashGenerator.generateBody(requestBody);
            PayRequestContext.setRequestHash(request, requestHash);
            IdempotencyScope scope = IdempotencyScope.from(request, principal, idempotencyKey);
            PayIdempotencyService.AcquireResult acquisition = idempotencyService.acquire(
                    scope,
                    requestHash);
            if (!acquisition.owner()) {
                idempotencyService.replay(response, acquisition.record());
                return;
            }

            ContentCachingResponseWrapper cachedResponse =
                    new ContentCachingResponseWrapper(response);
            boolean completed = false;
            try {
                filterChain.doFilter(
                        new CachedBodyHttpServletRequest(request, requestBody),
                        cachedResponse);
                idempotencyService.complete(
                        scope,
                        cachedResponse.getStatus(),
                        cachedResponse.getContentType(),
                        cachedResponse.getContentAsByteArray());
                completed = true;
            } finally {
                if (!completed) {
                    idempotencyService.release(scope);
                }
                cachedResponse.copyBodyToResponse();
            }
        } catch (PayException exception) {
            errorWriter.write(response, exception);
        }
    }
}
