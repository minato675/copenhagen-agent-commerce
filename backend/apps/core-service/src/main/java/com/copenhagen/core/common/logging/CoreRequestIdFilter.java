package com.copenhagen.core.common.logging;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 들어오는 요청마다 {@code X-Request-Id}를 MDC에 넣고 응답 헤더로 되돌려 준다(이슈 374, 구현순서 26).
 *
 * <p>앱·MCP가 보낸 값이 있으면 그대로 쓴다. 없으면 여기서 만든다. 어느 쪽이든 이 요청이 만든 모든 로그와
 * Merchant 호출에 같은 값이 실린다. 응답 헤더로 돌려주는 것은 호출한 쪽이 자기 로그와 맞춰 볼 수 있게 하기
 * 위해서다. 요청이 끝나면 키를 전부 지운다 — 서블릿 스레드는 재사용되므로 남기면 다음 요청 로그에 묻어 나간다.
 *
 * <p>이름이 {@code RequestContextFilter}가 아닌 이유. Spring Boot WebMvc 자동설정이 같은 이름의 Bean
 * ({@code org.springframework.web.filter.RequestContextFilter})을 등록해서 {@code @Component} 기본 이름이
 * 충돌하고 컨테이너가 기동에서 죽는다({@code ucp-merchant-mock}의 {@code RequestIdFilter}가 MR !109에서 겪었다).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CoreRequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** Merchant mock·Pay가 같은 헤더에 두는 상한과 같다. 이보다 길면 만든 값으로 대신한다. */
    static final int MAX_LENGTH = 100;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        CoreLogContext.put(CoreLogContext.REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            CoreLogContext.clear();
        }
    }

    static String resolve(String header) {
        if (header == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        return trimmed;
    }
}
