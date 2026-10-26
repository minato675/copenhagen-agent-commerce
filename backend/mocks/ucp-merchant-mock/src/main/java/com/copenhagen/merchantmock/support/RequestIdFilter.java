package com.copenhagen.merchantmock.support;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 들어오는 요청마다 {@code X-Request-Id} 를 MDC 에 넣고 응답 헤더로 되돌려 준다(DESIGN.md 12.1절, Jira 187).
 *
 * <p>Core 가 보낸 값이 있으면 그대로 쓴다. 없으면 여기서 만든다. 어느 쪽이든 이 요청이 만든 모든 로그, Pay 호출,
 * 웹훅에 같은 값이 실린다. 응답 헤더로 돌려주는 것은 Core 가 자기 로그와 맞춰 볼 수 있게 하기 위해서다.
 * 요청이 끝나면 다섯 키를 전부 지운다 — 서블릿 스레드는 재사용되므로 남기면 다음 요청 로그에 묻어 나간다.
 *
 * <p>이름이 {@code RequestContextFilter} 가 아닌 이유. Spring Boot WebMvc 자동설정이 같은 이름의 Bean
 * ({@code org.springframework.web.filter.RequestContextFilter})을 등록해서 {@code @Component} 기본 이름이 충돌했고
 * 컨테이너가 기동에서 죽었다(2026-09-17 MR !109 스모크 실패, {@code ApplicationBootPostgresTest} 가 재현).
 *
 * <p>요청 하나의 소요 시간도 여기서 찍는다(13절, Jira 472). 요청 전체를 감싸는 유일한 자리라 한 군데면 끝난다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** Pay 가 같은 헤더에 두는 상한과 같다. 이보다 길면 만든 값으로 대신한다. */
    static final int MAX_LENGTH = 100;

    /** 계측 줄의 접두어. 로그에서 이것만 뽑으면 된다. */
    static final String TIMING_PREFIX = "Merchant timing";

    /** 헬스체크는 10초마다 와서 안 빼면 로그가 그것으로 덮인다. 잴 것도 없다. */
    private static final String SKIP_PREFIX = "/actuator";

    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        LogContext.put(LogContext.REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            logTiming(requestId, request, response, started);
            LogContext.clear();
        }
    }

    /**
     * 판매처 구간 계측 줄. MCP 의 {@code MCP timing: …} 과 형태만 닮게 뒀고 그쪽 분석기가 읽지는 않는다 —
     * 정규식이 자기 stage 값만 받고, Core 가 {@code X-Request-Id} 를 안 보내 requestId 도 서로 다른 값이다.
     *
     * <p>체인이 예외로 끝나면 오류 응답을 만들기 전이라 status 가 아직 200 일 수 있다. 여기서 보는 것은 시간이다.
     */
    private static void logTiming(String requestId, HttpServletRequest request, HttpServletResponse response,
            long started) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith(SKIP_PREFIX)) {
            return;
        }
        LOG.info("{}: requestId={}, method={}, path={}, status={}, elapsedMs={}",
                TIMING_PREFIX, requestId, request.getMethod(), path, response.getStatus(),
                (System.nanoTime() - started) / 1_000_000);
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
