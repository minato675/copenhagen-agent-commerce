package com.copenhagen.pay.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.auth.ConfiguredBearerTokenVerifier;
import com.copenhagen.pay.common.auth.JwtBearerTokenVerifier;
import com.copenhagen.pay.common.auth.PayAuthProperties;
import com.copenhagen.pay.common.auth.PayAuthenticationFilter;
import com.copenhagen.pay.common.auth.PayJwtProperties;
import com.copenhagen.pay.common.auth.PayRouteAuthorizer;
import com.copenhagen.pay.common.auth.PayTokenVerifier;
import com.copenhagen.pay.common.error.ApiErrorWriter;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class PayAuthenticationObservabilityTest {

    private static final String SENSITIVE_TOKEN = "synthetic-Authorization-token-cookie";
    private final ApiErrorWriter errorWriter = new ApiErrorWriter(new ObjectMapper());
    private final PayRequestLogFilter requestLogFilter = new PayRequestLogFilter();

    @AfterEach
    void clearContext() {
        PayLogContext.clear();
    }

    @Test
    void acceptedLoginTokenIsLinkedToCompletionWithoutRawPrincipal() throws Exception {
        PayAuthenticationFilter authenticationFilter = filter(
                new ConfiguredBearerTokenVerifier(
                        new PayAuthProperties(Map.of("user-token", "user-001"), Map.of())));
        MockHttpServletRequest request = request("GET", "/api/pay/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer user-token");
        request.addHeader(PayRequestLogFilter.REQUEST_ID_HEADER, "auth-success-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (PayLogCapture logs = new PayLogCapture()) {
            requestLogFilter.doFilter(
                    request,
                    response,
                    (servletRequest, servletResponse) -> authenticationFilter.doFilter(
                            servletRequest,
                            servletResponse,
                            new MockFilterChain()));

            assertEquals(200, response.getStatus());
            assertEquals(1, logs.count("pay.auth.accepted"));
            assertEquals(1, logs.count("pay.request.complete"));
            assertTrue(logs.allText().contains("principalType=USER"));
            assertTrue(logs.allText().contains("authenticated=true"));
            assertTrue(logs.events().stream().anyMatch(event ->
                    PayLogContext.fingerprint("user-001").equals(
                            event.getMDCPropertyMap().get(PayLogContext.USER_ID))));
            assertTrue(!logs.allText().contains("user-001"));
        }
    }

    @Test
    void missingTokenIsLoggedAsAuthenticationFailureAndStillGetsCompletion() throws Exception {
        PayAuthenticationFilter authenticationFilter = filter(token -> Optional.empty());
        MockHttpServletRequest request = request("GET", "/api/pay/v1/payment-approvals");
        request.addHeader(PayRequestLogFilter.REQUEST_ID_HEADER, "auth-missing-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (PayLogCapture logs = new PayLogCapture()) {
            invoke(authenticationFilter, request, response);

            assertEquals(401, response.getStatus());
            assertEquals(1, logs.count("pay.auth.rejected"));
            assertEquals(1, logs.count("pay.request.complete"));
            assertTrue(logs.allText().contains("errorCode=AUTHENTICATION_REQUIRED"));
            assertTrue(logs.allText().contains("status=401"));
            assertTrue(!logs.allText().contains(SENSITIVE_TOKEN));
        }
    }

    @Test
    void expiredOrInvalidTokenIsClassifiedWithoutTokenEcho() throws Exception {
        PayAuthenticationFilter authenticationFilter = filter(token -> Optional.empty());
        MockHttpServletRequest request = request("GET", "/api/pay/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer " + SENSITIVE_TOKEN);
        request.addHeader(PayRequestLogFilter.REQUEST_ID_HEADER, "auth-expired-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (PayLogCapture logs = new PayLogCapture()) {
            invoke(authenticationFilter, request, response);

            assertEquals(401, response.getStatus());
            assertEquals(1, logs.count("pay.auth.rejected"));
            assertTrue(logs.allText().contains("errorCode=INVALID_TOKEN"));
            assertTrue(!logs.allText().contains(SENSITIVE_TOKEN));
        }
    }

    @Test
    void expiredJwtIsClassifiedAsInvalidTokenWithoutJwtPayload() throws Exception {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        String keyId = "core-access-key-1";
        ECKey signingKey = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_256)
                .keyID(keyId)
                .algorithm(JWSAlgorithm.ES256)
                .generate();
        JwtBearerTokenVerifier verifier = new JwtBearerTokenVerifier(
                new PayJwtProperties(
                        true,
                        "https://core.example.com",
                        "pay-service",
                        JSONObjectUtils.toJSONString(
                                new JWKSet(signingKey.toPublicJWK()).toJSONObject()),
                        0),
                Clock.fixed(now, ZoneOffset.UTC));
        SignedJWT expired = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .type(JOSEObjectType.JWT)
                        .keyID(keyId)
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer("https://core.example.com")
                        .subject("user-001")
                        .audience("pay-service")
                        .expirationTime(Date.from(now.minusSeconds(1)))
                        .build());
        expired.sign(new ECDSASigner(signingKey));
        String serializedToken = expired.serialize();
        PayAuthenticationFilter authenticationFilter = filter(verifier);
        MockHttpServletRequest request = request("GET", "/api/pay/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer " + serializedToken);
        request.addHeader(PayRequestLogFilter.REQUEST_ID_HEADER, "auth-expired-jwt-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (PayLogCapture logs = new PayLogCapture()) {
            invoke(authenticationFilter, request, response);

            assertEquals(401, response.getStatus());
            assertTrue(logs.allText().contains("errorCode=INVALID_TOKEN"));
            assertTrue(!logs.allText().contains(serializedToken));
        }
    }

    @Test
    void forbiddenPrincipalIsDistinguishedFromAuthenticationFailure() throws Exception {
        PayAuthProperties properties = new PayAuthProperties(
                Map.of("user-token", "user-001"),
                Map.of(),
                Map.of());
        PayAuthenticationFilter authenticationFilter = new PayAuthenticationFilter(
                new ConfiguredBearerTokenVerifier(properties),
                errorWriter,
                PayRouteAuthorizer.withMerchantRegistration(merchantId -> true));
        MockHttpServletRequest request = request("POST", "/internal/v1/payment-approvals");
        request.addHeader("Authorization", "Bearer user-token");
        request.addHeader(PayRequestLogFilter.REQUEST_ID_HEADER, "auth-forbidden-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (PayLogCapture logs = new PayLogCapture()) {
            invoke(authenticationFilter, request, response);

            assertEquals(403, response.getStatus());
            assertEquals(0, logs.count("pay.auth.accepted"));
            assertEquals(1, logs.count("pay.auth.rejected"));
            assertTrue(logs.allText().contains("errorCode=FORBIDDEN"));
            assertTrue(logs.allText().contains("authentication=VERIFIED"));
            assertTrue(logs.allText().contains("status=403"));
        }
    }

    private void invoke(
            PayAuthenticationFilter authenticationFilter,
            MockHttpServletRequest request,
            MockHttpServletResponse response) throws Exception {
        requestLogFilter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> authenticationFilter.doFilter(
                        servletRequest,
                        servletResponse,
                        new MockFilterChain()));
    }

    private PayAuthenticationFilter filter(
            ConfiguredBearerTokenVerifier tokenVerifier) {
        return new PayAuthenticationFilter(
                tokenVerifier,
                errorWriter,
                PayRouteAuthorizer.withMerchantRegistration(merchantId -> true));
    }

    private PayAuthenticationFilter filter(PayTokenVerifier tokenVerifier) {
        return new PayAuthenticationFilter(
                tokenVerifier,
                errorWriter,
                PayRouteAuthorizer.withMerchantRegistration(merchantId -> true));
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

}
