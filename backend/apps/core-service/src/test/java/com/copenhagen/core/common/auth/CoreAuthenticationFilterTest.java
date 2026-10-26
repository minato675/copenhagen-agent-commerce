package com.copenhagen.core.common.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.copenhagen.core.auth.jwt.CoreAuthJwtKeySet;
import com.copenhagen.core.auth.jwt.CoreJwtIssuer;
import com.copenhagen.core.auth.jwt.CoreJwtVerifier;
import com.copenhagen.core.common.error.CoreApiErrorWriter;
import com.copenhagen.core.common.logging.CoreLogContext;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAuthenticationFilterTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private final CoreAuthJwtKeySet keySet = new CoreAuthJwtKeySet("", "");
    private final CoreJwtIssuer issuer = new CoreJwtIssuer(keySet, FIXED_CLOCK);
    private final CoreJwtVerifier verifier = new CoreJwtVerifier(keySet, FIXED_CLOCK);
    private final CoreApiErrorWriter errorWriter = new CoreApiErrorWriter(new ObjectMapper());
    private final CoreAuthenticationFilter filter = new CoreAuthenticationFilter(verifier, errorWriter);

    @AfterEach
    void clearLogContext() {
        CoreLogContext.clear();
    }

    @Test
    @DisplayName("공개 라우트는 토큰 없이도 통과한다")
    void shouldNotFilter_publicRoutes_alwaysTrue() {
        assertTrue(filter.shouldNotFilter(request("GET", "/api/core/v1/auth/login")));
        assertTrue(filter.shouldNotFilter(request("POST", "/api/core/v1/auth/signup")));
        assertTrue(filter.shouldNotFilter(request("POST", "/api/core/v1/auth/token/refresh")));
        assertTrue(filter.shouldNotFilter(request("POST", "/api/core/v1/auth/logout")));
    }

    @Test
    @DisplayName("/users/me는 보호 대상 라우트라 shouldNotFilter가 false다(순서 10부터 실제 보호 시작)")
    void shouldNotFilter_usersMe_requiresAuthentication() {
        assertFalse(filter.shouldNotFilter(request("GET", "/api/core/v1/users/me")));
    }

    @Test
    @DisplayName("유효한 토큰이면 통과하고 요청 컨텍스트에 principal을 저장한다")
    void doFilterInternal_validToken_setsContextAndContinues() throws Exception {
        UUID subject = UUID.randomUUID();
        String token = issuer.issue(subject).token();
        MockHttpServletRequest request = request("GET", "/protected");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(Optional.of(subject), CoreRequestContext.findPrincipal(request));
        assertEquals(subject.toString(), CoreLogContext.get(CoreLogContext.USER_ID));
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 401 AUTH_TOKEN_MISSING을 응답하고 체인을 진행하지 않는다")
    void doFilterInternal_missingHeader_rejects() throws Exception {
        MockHttpServletRequest request = request("GET", "/protected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_TOKEN_MISSING"));
        assertFalse(CoreRequestContext.findPrincipal(request).isPresent());
    }

    @Test
    @DisplayName("잘못된(위조/만료) 토큰은 401 AUTH_TOKEN_INVALID를 응답한다")
    void doFilterInternal_invalidToken_rejects() throws Exception {
        MockHttpServletRequest request = request("GET", "/protected");
        request.addHeader("Authorization", "Bearer not-a-real-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_TOKEN_INVALID"));
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }
}
