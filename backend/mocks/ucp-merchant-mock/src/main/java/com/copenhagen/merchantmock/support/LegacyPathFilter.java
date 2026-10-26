package com.copenhagen.merchantmock.support;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 옛 경로를 첫 몰의 새 경로로 forward 한다(DESIGN.md 3.3절, 393).
 *
 * <p>393 이 경로를 {@code /api/ucp/v1/{merchantId}/...} 로 바꿨는데 Core 의 {@code MerchantCatalogClient} 는
 * base-url 뒤에 {@code /ucp/v1/catalog/*} 를 하드코딩하고 Pay 의 {@code HttpMerchantPublicKeyClient} 는
 * {@code /.well-known/ucp} 를 붙인다(2026-09-17 실측). 별칭이 없으면 머지되는 순간 둘이 깨진다.
 *
 * <pre>
 * /.well-known/ucp                     →  /api/ucp/v1/{첫 몰}/.well-known/ucp
 * /ucp/v1/**                           →  /api/ucp/v1/{첫 몰}/**
 * /api/ucp/v1/{몰}/ucp/v1/**            →  /api/ucp/v1/{몰}/**      (Core 의 base-url 뒤 하드코딩 흡수)
 * </pre>
 *
 * <p>컨트롤러는 새 경로 하나만 안다. Core 가 몰별 base-url 로 옮기고 Pay 가 {@code profile-path} 를 바꾸면 이 클래스를
 * 지운다(11절). {@link RequestIdFilter} 뒤에 서서 forward 한 요청도 같은 requestId 를 갖게 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class LegacyPathFilter extends OncePerRequestFilter {

    static final String LEGACY_PROFILE = "/.well-known/ucp";
    static final String LEGACY_REST_PREFIX = "/ucp/v1/";

    private final MerchantProperties properties;

    public LegacyPathFilter(MerchantProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String target = rewrite(request.getRequestURI(), properties.defaultMall().id());
        if (target == null) {
            chain.doFilter(request, response);
            return;
        }
        request.getRequestDispatcher(target).forward(request, response);
    }

    /** 별칭이 아니면 null. 그대로 흘려보낸다. */
    static String rewrite(String uri, String defaultMerchantId) {
        String base = MerchantProperties.API_PREFIX + "/" + defaultMerchantId;
        if (LEGACY_PROFILE.equals(uri)) {
            return base + LEGACY_PROFILE;
        }
        if (uri.startsWith(LEGACY_REST_PREFIX)) {
            return base + uri.substring(LEGACY_REST_PREFIX.length() - 1);
        }
        // Core 가 몰별 base-url(/api/ucp/v1/merchant-b) 뒤에 옛 접두어 /ucp/v1 을 코드로 붙여 보내는 경우.
        // 몰 세그먼트 바로 뒤의 /ucp/v1 을 떼어 준다. Core 는 yml 항목만 더하고 코드를 안 고쳐도 된다.
        String prefix = MerchantProperties.API_PREFIX + "/";
        if (uri.startsWith(prefix)) {
            int mallEnd = uri.indexOf('/', prefix.length());
            if (mallEnd > 0 && uri.startsWith(LEGACY_REST_PREFIX, mallEnd)) {
                return uri.substring(0, mallEnd) + uri.substring(mallEnd + LEGACY_REST_PREFIX.length() - 1);
            }
        }
        return null;
    }
}
