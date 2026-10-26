package com.copenhagen.core.common.logging;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CoreRequestIdFilterTest {

    private final CoreRequestIdFilter filter = new CoreRequestIdFilter();

    @AfterEach
    void clearContext() {
        CoreLogContext.clear();
    }

    @Test
    @DisplayName("보낸 X-Request-Id가 있으면 그대로 쓴다")
    void resolve_givenHeader_keepsIt() {
        assertThat(CoreRequestIdFilter.resolve("core-req-42")).isEqualTo("core-req-42");
    }

    @Test
    @DisplayName("헤더가 없거나 비었거나 상한을 넘으면 새 값을 만든다")
    void resolve_missingOrUnusable_generates() {
        String generated = CoreRequestIdFilter.resolve(null);
        String fromBlank = CoreRequestIdFilter.resolve("   ");
        String fromTooLong = CoreRequestIdFilter.resolve("x".repeat(CoreRequestIdFilter.MAX_LENGTH + 1));

        assertThat(generated).isNotBlank();
        assertThat(fromBlank).isNotBlank().isNotEqualTo(generated);
        assertThat(fromTooLong).isNotBlank().hasSizeLessThanOrEqualTo(CoreRequestIdFilter.MAX_LENGTH);
    }

    @Test
    @DisplayName("상한과 같은 길이는 그대로 쓴다")
    void resolve_atMaxLength_keepsIt() {
        String header = "x".repeat(CoreRequestIdFilter.MAX_LENGTH);

        assertThat(CoreRequestIdFilter.resolve(header)).isEqualTo(header);
    }

    @Test
    @DisplayName("요청 중에는 MDC에 값이 있고 응답 헤더로 같은 값을 돌려준다")
    void doFilterInternal_putsContextAndEchoesHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/core/v1/users/me");
        request.addHeader(CoreRequestIdFilter.HEADER, "core-req-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        StringBuilder seenInsideChain = new StringBuilder();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
                seenInsideChain.append(CoreLogContext.get(CoreLogContext.REQUEST_ID));
            }
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(seenInsideChain).hasToString("core-req-42");
        assertThat(response.getHeader(CoreRequestIdFilter.HEADER)).isEqualTo("core-req-42");
    }

    @Test
    @DisplayName("요청이 끝나면 MDC를 비운다 — 서블릿 스레드는 재사용된다")
    void doFilterInternal_clearsContextAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/core/v1/users/me");
        CoreLogContext.put(CoreLogContext.USER_ID, "leftover-from-previous-request");

        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(CoreLogContext.get(CoreLogContext.REQUEST_ID)).isNull();
        assertThat(CoreLogContext.get(CoreLogContext.USER_ID)).isNull();
    }
}
