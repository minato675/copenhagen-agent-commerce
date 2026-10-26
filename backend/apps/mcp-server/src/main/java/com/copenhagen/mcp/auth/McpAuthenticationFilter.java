package com.copenhagen.mcp.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

public final class McpAuthenticationFilter extends OncePerRequestFilter {

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final McpAuthenticationService authentication;
    private final McpAuthResponses responses;
    private final Map<String, SessionOwner> sessionOwners = new ConcurrentHashMap<>();

    public McpAuthenticationFilter(McpAuthenticationService authentication, McpAuthResponses responses) {
        this.authentication = authentication;
        this.responses = responses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        McpIdentity identity;
        String session;
        try {
            identity = authentication.authenticate(bearerToken(request));
            session = sessionId(request);
            if (session != null) {
                SessionOwner owner = sessionOwners.get(session);
                if (owner == null) {
                    throw new McpAuthException(McpAuthError.SESSION_NOT_FOUND);
                }
                if (!owner.equals(SessionOwner.from(identity))) {
                    throw new McpAuthException(McpAuthError.ACCESS_DENIED);
                }
            }
        } catch (McpAuthException exception) {
            responses.reject(response, exception);
            return;
        }
        request.setAttribute(McpIdentity.CONTEXT_KEY, identity);
        // SDK가 세션 ID를 클라이언트에 노출하기 전에 소유자를 연결한다.
        HttpServletResponseWrapper boundResponse = new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                bindSession(name, value, identity);
                super.setHeader(name, value);
            }

            @Override
            public void addHeader(String name, String value) {
                bindSession(name, value, identity);
                super.addHeader(name, value);
            }
        };
        try {
            chain.doFilter(request, boundResponse);
        } finally {
            request.removeAttribute(McpIdentity.CONTEXT_KEY);
            if (session != null && "DELETE".equals(request.getMethod()) && response.getStatus() == 200) {
                sessionOwners.remove(session, SessionOwner.from(identity));
            }
        }
    }

    private String bearerToken(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new McpAuthException(McpAuthError.INVALID_AUTH_REQUEST);
        }
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            throw new McpAuthException(McpAuthError.AUTHENTICATION_REQUIRED);
        }
        if (headers.size() != 1 || !headers.getFirst().matches("(?i)Bearer [A-Za-z0-9._~+/-]+=*")
                || headers.getFirst().length() > 8192) {
            throw new McpAuthException(McpAuthError.INVALID_AUTH_REQUEST);
        }
        return headers.getFirst().substring(7);
    }

    private String sessionId(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(SESSION_HEADER));
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1 || !values.getFirst().matches("[A-Za-z0-9_-]{1,128}")) {
            throw new McpAuthException(McpAuthError.INVALID_AUTH_REQUEST);
        }
        return values.getFirst();
    }

    private void bindSession(String name, String value, McpIdentity identity) {
        if (SESSION_HEADER.equalsIgnoreCase(name)) {
            SessionOwner owner = SessionOwner.from(identity);
            SessionOwner existing = sessionOwners.putIfAbsent(value, owner);
            if (existing != null && !existing.equals(owner)) {
                throw new McpAuthException(McpAuthError.ACCESS_DENIED);
            }
        }
    }

    private record SessionOwner(String publicUserRef, String connectionRef) {

        private static SessionOwner from(McpIdentity identity) {
            return new SessionOwner(identity.publicUserRef(), identity.connectionRef());
        }
    }
}
