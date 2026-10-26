package com.copenhagen.core.common.auth;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.copenhagen.core.auth.jwt.CoreJwtVerifier;
import com.copenhagen.core.common.error.CoreApiErrorWriter;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.logging.CoreLogContext;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CoreAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final CoreJwtVerifier jwtVerifier;
    private final CoreApiErrorWriter errorWriter;

    public CoreAuthenticationFilter(CoreJwtVerifier jwtVerifier, CoreApiErrorWriter errorWriter) {
        this.jwtVerifier = jwtVerifier;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!DispatcherType.REQUEST.equals(request.getDispatcherType())) {
            return true;
        }
        return !CoreAuthenticationPolicy.requiresAuthentication(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String bearerToken = extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (bearerToken == null) {
            errorWriter.write(response, CoreErrorCode.AUTH_TOKEN_MISSING);
            return;
        }

        Optional<UUID> principal = jwtVerifier.verify(bearerToken);
        if (principal.isEmpty()) {
            errorWriter.write(response, CoreErrorCode.AUTH_TOKEN_INVALID);
            return;
        }

        CoreRequestContext.setPrincipal(request, principal.get());
        CoreLogContext.put(CoreLogContext.USER_ID, principal.get().toString());
        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
