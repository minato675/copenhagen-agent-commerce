package com.copenhagen.merchantmock.support;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 옛 경로 별칭(393, DESIGN.md 3.3절). Core 의 {@code /ucp/v1/catalog/*} 하드코딩과 Pay 의 {@code /.well-known/ucp} 가
 * 첫 몰로 forward 되는지 본다. 새 경로는 손대지 않고 흘려보낸다.
 */
class LegacyPathFilterTest {

    @Test
    @DisplayName("옛 REST 경로는 첫 몰의 새 경로로 바뀐다")
    void rewrite_legacyRestPath_prefixesFirstMall() {
        // given & when & then
        assertEquals("/api/ucp/v1/merchant-a/catalog/search",
                LegacyPathFilter.rewrite("/ucp/v1/catalog/search", "merchant-a"));
        assertEquals("/api/ucp/v1/merchant-a/checkout-sessions/co-1/complete",
                LegacyPathFilter.rewrite("/ucp/v1/checkout-sessions/co-1/complete", "merchant-a"));
        assertEquals("/api/ucp/v1/merchant-a/orders/o-1",
                LegacyPathFilter.rewrite("/ucp/v1/orders/o-1", "merchant-a"));
    }

    @Test
    @DisplayName("몰별 base-url 뒤에 옛 접두어 /ucp/v1 이 붙어 오면 떼어 낸다. Core 가 yml 만 고치면 되게")
    void rewrite_mallBaseUrlWithLegacyPrefix_stripsPrefix() {
        assertEquals("/api/ucp/v1/merchant-b/catalog/search",
                LegacyPathFilter.rewrite("/api/ucp/v1/merchant-b/ucp/v1/catalog/search", "merchant-a"));
        assertEquals("/api/ucp/v1/merchant-c/checkout-sessions/co-1",
                LegacyPathFilter.rewrite("/api/ucp/v1/merchant-c/ucp/v1/checkout-sessions/co-1", "merchant-a"));
        assertNull(LegacyPathFilter.rewrite("/api/ucp/v1/merchant-b/catalog/search", "merchant-a"));
    }

    @Test
    @DisplayName("옛 Profile 경로는 첫 몰의 Profile 로 바뀐다")
    void rewrite_legacyProfile_prefixesFirstMall() {
        assertEquals("/api/ucp/v1/merchant-a/.well-known/ucp",
                LegacyPathFilter.rewrite("/.well-known/ucp", "merchant-a"));
    }

    @Test
    @DisplayName("새 경로와 내부 경로는 건드리지 않는다")
    void rewrite_newOrInternalPath_returnsNull() {
        assertNull(LegacyPathFilter.rewrite("/api/ucp/v1/merchant-b/catalog/search", "merchant-a"));
        assertNull(LegacyPathFilter.rewrite("/internal/v1/scenarios", "merchant-a"));
        assertNull(LegacyPathFilter.rewrite("/actuator/health", "merchant-a"));
        assertNull(LegacyPathFilter.rewrite("/ucp/v2/catalog/search", "merchant-a"));
    }

    @Test
    @DisplayName("필터는 별칭이면 forward 하고 아니면 체인을 계속 탄다")
    void doFilter_forwardsLegacyAndPassesNew() throws Exception {
        // given
        LegacyPathFilter filter = new LegacyPathFilter(MerchantPropertiesFixture.defaults());
        AtomicReference<String> forwarded = new AtomicReference<>();
        MockHttpServletRequest legacy = new MockHttpServletRequest("GET", "/.well-known/ucp") {
            @Override
            public RequestDispatcher getRequestDispatcher(String path) {
                forwarded.set(path);
                return new RequestDispatcher() {
                    @Override
                    public void forward(ServletRequest request, ServletResponse response) {
                    }

                    @Override
                    public void include(ServletRequest request, ServletResponse response) {
                    }
                };
            }
        };
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilter(legacy, new MockHttpServletResponse(), chain);

        // then
        assertEquals("/api/ucp/v1/merchant-a/.well-known/ucp", forwarded.get());
        assertNull(chain.getRequest(), "forward 했으면 체인은 타지 않는다");

        // given: 새 경로
        MockHttpServletRequest fresh = new MockHttpServletRequest("POST", "/api/ucp/v1/merchant-b/catalog/search");
        MockFilterChain passThrough = new MockFilterChain();

        // when
        filter.doFilter(fresh, new MockHttpServletResponse(), passThrough);

        // then
        assertEquals(fresh, passThrough.getRequest());
    }
}
