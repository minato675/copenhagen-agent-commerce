package com.copenhagen.core.common.logging;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreAccessLogFilterTest {

    private final CoreAccessLogFilter filter = new CoreAccessLogFilter();

    @AfterEach
    void clearContext() {
        CoreLogContext.clear();
    }

    @Test
    @DisplayName("요청 한 건에 method·path·status·durationMs를 남긴다")
    void doFilterInternal_success_writesOneLine() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/core/v1/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            filter.doFilterInternal(request, response, new MockFilterChain());

            String line = recorder.singleLine();
            assertThat(line).contains("\"event\":\"http.access\"");
            assertThat(line).contains("\"method\":\"GET\"");
            assertThat(line).contains("\"path\":\"/api/core/v1/users/me\"");
            assertThat(line).contains("\"status\":200");
            assertThat(line).contains("\"durationMs\":");
        }
    }

    @Test
    @DisplayName("오류 응답이면 MDC의 errorCode가 줄에 실린다")
    void doFilterInternal_errorCodeInContext_isLogged() throws Exception {
        MockHttpServletRequest request = request("DELETE", "/api/core/v1/addresses/a-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
                CoreLogContext.put(CoreLogContext.ERROR_CODE, "ADDRESS_NOT_FOUND");
                ((MockHttpServletResponse) servletResponse).setStatus(404);
            }
        };

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            filter.doFilterInternal(request, response, chain);

            assertThat(recorder.singleLine())
                    .contains("\"status\":404")
                    .contains("\"errorCode\":\"ADDRESS_NOT_FOUND\"");
        }
    }

    @Test
    @DisplayName("체인에서 예외가 빠져나가면 500으로 남기고 예외는 그대로 던진다")
    void doFilterInternal_exception_logsInternalErrorAndRethrows() {
        MockHttpServletRequest request = request("GET", "/api/core/v1/products/p-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
                throw new IllegalStateException("boom");
            }
        };

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(recorder.singleLine()).contains("\"status\":500");
        }
    }

    @Test
    @DisplayName("쿼리 스트링은 남기지 않는다")
    void doFilterInternal_queryString_isNotLogged() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/core/v1/products/search");
        request.setQueryString("query=secret-term");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(recorder.singleLine()).doesNotContain("secret-term");
        }
    }

    @Test
    @DisplayName("본문이 있으면 크기만 남기고 내용은 남기지 않는다")
    void doFilterInternal_body_logsSizeOnly() throws Exception {
        byte[] body = "{\"loginId\":\"tester\",\"password\":\"P@ssw0rd!\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request("POST", "/api/core/v1/auth/login");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(recorder.singleLine())
                    .contains("\"requestBytes\":" + body.length)
                    .doesNotContain("P@ssw0rd!")
                    .doesNotContain("tester");
        }
    }

    @Test
    @DisplayName("본문이 없는 요청은 requestBytes 키를 쓰지 않는다")
    void doFilterInternal_withoutBody_omitsRequestBytes() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/core/v1/addresses");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(recorder.singleLine()).doesNotContain("requestBytes");
        }
    }

    @Test
    @DisplayName("X-Forwarded-For가 있으면 첫 홉을 clientIp로 쓴다")
    void doFilterInternal_forwardedFor_usesFirstHop() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/core/v1/addresses");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(recorder.singleLine()).contains("\"clientIp\":\"203.0.113.7\"");
        }
    }

    @Test
    @DisplayName("/actuator는 기록하지 않는다 — healthcheck가 10초마다 친다")
    void shouldNotFilter_actuator_isSkipped() {
        assertThat(filter.shouldNotFilter(request("GET", "/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(request("GET", "/api/core/v1/users/me"))).isFalse();
    }

    @Test
    @DisplayName("ERROR 디스패치는 건너뛴다 — 한 요청이 두 줄이 되지 않는다")
    void shouldNotFilterErrorDispatch_isTrue() {
        assertThat(filter.shouldNotFilterErrorDispatch()).isTrue();
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("10.0.1.7");
        return request;
    }
}
