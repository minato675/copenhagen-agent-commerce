package com.copenhagen.core.common.logging;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 한 건이 어떤 결과로 끝났는지 {@code access} 로거에 한 줄 남긴다(이슈 374, 구현순서 26).
 *
 * <p>{@link CoreRequestIdFilter}(HIGHEST_PRECEDENCE) 바로 안쪽, 인증 필터(+10) 바깥이다. 그래서 인증 필터가
 * 직접 써 보내는 401·403도 기록된다.
 *
 * <p>본문·헤더는 기록하지 않는다(설계서 §3 결정 2·5). 응답 크기도 남기지 않는다 — 재려면
 * {@code ContentCachingResponseWrapper}로 본문 전체를 버퍼링해야 하고, 얻는 정보에 비해 대가가 크다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CoreAccessLogFilter extends OncePerRequestFilter {

    private static final String EVENT = "http.access";
    private static final String ACTUATOR_PREFIX = "/actuator";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    /**
     * compose healthcheck가 10초마다 {@code /actuator/health}를 친다. 그대로 두면 하루 8,640줄이 실제 API
     * 호출을 덮는다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ACTUATOR_PREFIX);
    }

    /** ERROR 디스패치까지 기록하면 한 요청이 두 줄이 된다. 원래 디스패치에서 한 번만 남긴다. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            // 필터 밖으로 빠져나가는 예외는 컨테이너가 500으로 바꾼다. 응답에는 아직 그 상태가 없다.
            write(request, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, startedAt);
            throw exception;
        }
        write(request, response.getStatus(), startedAt);
    }

    private void write(HttpServletRequest request, int status, long startedAt) {
        AccessLogLine.event(EVENT)
                .with("method", request.getMethod())
                .with("path", request.getRequestURI())
                .with("status", status)
                .with("errorCode", CoreLogContext.get(CoreLogContext.ERROR_CODE))
                .with("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                .with("requestBytes", requestBytes(request))
                .with("clientIp", clientIp(request))
                .log();
    }

    /** {@code Content-Length}가 없는 요청(GET·DELETE)은 -1이다. 그때는 키를 쓰지 않는다. */
    private Long requestBytes(HttpServletRequest request) {
        long length = request.getContentLengthLong();
        return length < 0 ? null : length;
    }

    /**
     * Nginx 뒤라 {@code getRemoteAddr()}만으로는 프록시 주소가 잡힌다. 프록시가 헤더를 넣지 않으면 그대로
     * 프록시 주소가 남는다 — 설계서 §9에 적어 둔 한계다.
     */
    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return request.getRemoteAddr();
        }
        int firstHopEnd = forwardedFor.indexOf(',');
        String firstHop = firstHopEnd < 0 ? forwardedFor : forwardedFor.substring(0, firstHopEnd);
        return firstHop.trim();
    }
}
