package com.copenhagen.merchantmock.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 요청 하나의 requestId 수명(DESIGN.md 12.1절). 받은 것을 쓰고, 없으면 만들고, 끝나면 지운다.
 * 계측 줄(13절, Jira 472)도 여기서 나오므로 같이 본다.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLog() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger(RequestIdFilter.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    /** 계측 줄만 골라 낸다. 형식이 바뀌면 이 메서드 하나만 고치면 된다. */
    private List<String> timingLines() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(RequestIdFilter.TIMING_PREFIX))
                .toList();
    }

    @Test
    @DisplayName("Core 가 보낸 X-Request-Id 를 MDC 에 넣고 응답 헤더로 되돌려 준다")
    void doFilter_withHeader_usesItAndEchoes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ucp/v1/merchant-a/checkout-sessions");
        request.addHeader(RequestIdFilter.HEADER, "core-req-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(LogContext.REQUEST_ID)));

        assertEquals("core-req-42", seen.get(), "체인 안에서는 MDC 에 있어야 한다");
        assertEquals("core-req-42", response.getHeader(RequestIdFilter.HEADER));
        assertNull(MDC.get(LogContext.REQUEST_ID), "요청이 끝나면 지운다. 서블릿 스레드는 재사용된다");
    }

    @Test
    @DisplayName("헤더가 없으면 만들어서 쓰고 그 값을 응답 헤더로 알려 준다")
    void doFilter_withoutHeader_generates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ucp/v1/merchant-a/orders/order-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(LogContext.REQUEST_ID)));

        assertNotNull(seen.get());
        assertEquals(seen.get(), response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    @DisplayName("빈 값이나 100자를 넘는 값은 믿지 않고 새로 만든다")
    void resolve_blankOrTooLong_generates() {
        assertNotEquals("", RequestIdFilter.resolve("   "));
        String tooLong = "x".repeat(RequestIdFilter.MAX_LENGTH + 1);
        assertNotEquals(tooLong, RequestIdFilter.resolve(tooLong));
        assertEquals("ok", RequestIdFilter.resolve(" ok "));
    }

    @Test
    @DisplayName("체인이 예외로 끝나도 MDC 는 지운다")
    void doFilter_chainThrows_stillClears() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(RequestIdFilter.HEADER, "req-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // 예외는 그대로 올라간다. 여기서 보는 것은 뒷정리다
        }

        assertNull(MDC.get(LogContext.REQUEST_ID));
    }

    @Test
    @DisplayName("요청이 끝나면 계측 줄 하나를 남긴다 — requestId·method·path·status·elapsedMs")
    void doFilter_logsTimingLine() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ucp/v1/merchant-a/catalog/search");
        request.addHeader(RequestIdFilter.HEADER, "req-timing-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));

        List<String> lines = timingLines();
        assertEquals(1, lines.size(), "요청 하나에 계측 줄 하나다");
        String line = lines.get(0);
        assertTrue(line.contains("requestId=req-timing-1"), line);
        assertTrue(line.contains("method=POST"), line);
        assertTrue(line.contains("path=/api/ucp/v1/merchant-a/catalog/search"), line);
        assertTrue(line.contains("status=200"), line);
        assertTrue(line.matches(".*elapsedMs=\\d+$"), "elapsedMs 가 줄 끝에 숫자로 와야 파싱이 쉽다: " + line);
    }

    @Test
    @DisplayName("actuator 요청은 안 찍는다. 헬스체크가 10초마다 와서 로그를 덮는다")
    void doFilter_actuator_isNotLogged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertTrue(timingLines().isEmpty(), "헬스체크는 계측 대상이 아니다");
    }

    @Test
    @DisplayName("체인이 예외로 끝나도 계측 줄은 남는다 — 느린 요청이 실패로 끝날 때가 제일 알고 싶다")
    void doFilter_chainThrows_stillLogsTiming() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ucp/v1/merchant-a/orders/order-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // 예외는 그대로 올라간다. 여기서 보는 것은 계측 줄이 남느냐다
        }

        assertEquals(1, timingLines().size());
    }
}
