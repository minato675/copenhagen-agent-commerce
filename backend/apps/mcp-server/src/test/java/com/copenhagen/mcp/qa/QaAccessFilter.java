package com.copenhagen.mcp.qa;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/** loopback QA 전용으로 생성한 일회성 신원을 기존 인증 경계로 전달한다. */
final class QaAccessFilter implements Filter {

    private final String token;
    private final boolean catalog;
    private final boolean cart;
    private final boolean handoff;

    QaAccessFilter(String token) {
        this(token, false);
    }

    QaAccessFilter(String token, boolean catalog) {
        this(token, catalog, false);
    }

    QaAccessFilter(String token, boolean catalog, boolean cart) {
        this(token, catalog, cart, false);
    }

    QaAccessFilter(String token, boolean catalog, boolean cart, boolean handoff) {
        this.token = token;
        this.catalog = catalog;
        this.cart = cart;
        this.handoff = handoff;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse output = (HttpServletResponse) response;
        if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(http.getRemoteAddr())) {
            output.setStatus(403);
            return;
        }
        output.setHeader("X-Copenhagen-Qa", handoff ? "handoff-mock"
                : cart ? "cart-mock" : catalog ? "catalog-mock" : "connection-only");
        output.setHeader("Cache-Control", "no-store");
        String path = http.getRequestURI();
        if (path.equals("/qa") || path.equals("/qa/check") || (catalog && path.equals("/qa/tools"))) {
            String host = http.getHeader("Host");
            String origin = http.getHeader("Origin");
            if (host == null || !host.matches("(localhost|127\\.0\\.0\\.1):[0-9]+")
                    || (origin != null && !origin.equals("http://" + host))) {
                output.setStatus(403);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (!path.equals("/mcp")) {
            output.setStatus(404);
            return;
        }
        if (http.getHeaders("Authorization").hasMoreElements()) {
            output.setStatus(400);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(http) {
            @Override
            public String getHeader(String name) {
                return name.equalsIgnoreCase("Authorization") ? "Bearer " + token : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return name.equalsIgnoreCase("Authorization")
                        ? Collections.enumeration(List.of("Bearer " + token)) : super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                List<String> names = Collections.list(super.getHeaderNames());
                names.add("Authorization");
                return Collections.enumeration(names);
            }
        }, response);
    }
}
