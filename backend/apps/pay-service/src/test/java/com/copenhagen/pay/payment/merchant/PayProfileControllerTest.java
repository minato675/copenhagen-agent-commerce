package com.copenhagen.pay.payment.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.copenhagen.pay.payment.credential.mandate.MandateKeySet;
import com.copenhagen.pay.payment.credential.mandate.MandateProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class PayProfileControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void returnsOnlyPublicMandateJwksWithPrivateCachePolicy() throws Exception {
        KeyPair keyPair = keyPair();
        ECKey publicKey = publicKey(keyPair, "pay-signing-key-1");
        MandateProperties properties = new MandateProperties(
                "pay-service",
                "pay-signing-key-1",
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(keyPair.getPrivate().getEncoded()),
                "",
                publicKey.toJSONString(),
                "[]",
                300,
                true);
        PayProfileController controller = new PayProfileController(new MandateKeySet(
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper()));

        ResponseEntity<PayProfileResponse> response = controller.find(authenticatedRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("pay-service", response.getBody().issuer());
        assertEquals(1, response.getBody().keys().size());
        PayProfileResponse.PublicJwk key = response.getBody().keys().getFirst();
        assertEquals("pay-signing-key-1", key.kid());
        assertEquals("EC", key.kty());
        assertEquals("sig", key.use());
        assertEquals("ES256", key.alg());
        assertEquals("P-256", key.crv());
        assertEquals(publicKey.getX().toString(), key.x());
        assertEquals(publicKey.getY().toString(), key.y());
        assertEquals(
                "max-age=300, must-revalidate, private",
                response.getHeaders().getFirst("Cache-Control"));
        String serialized = new ObjectMapper().writeValueAsString(response.getBody());
        org.junit.jupiter.api.Assertions.assertFalse(serialized.contains("\"d\""));
    }

    @Test
    void rejectsUserPrincipalBeforeReturningProfile() throws Exception {
        KeyPair keyPair = keyPair();
        ECKey publicKey = publicKey(keyPair, "pay-signing-key-1");
        MandateProperties properties = new MandateProperties(
                "pay-service",
                "pay-signing-key-1",
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(keyPair.getPrivate().getEncoded()),
                "",
                publicKey.toJSONString(),
                "[]",
                300,
                true);
        PayProfileController controller = new PayProfileController(new MandateKeySet(
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/merchant/v1/profile");
        PayRequestContext.setPrincipal(request, new PayPrincipal(PayPrincipal.Type.USER, "user-001"));

        PayException exception = assertThrows(PayException.class, () -> controller.find(request));

        assertEquals(PayErrorCode.FORBIDDEN, exception.errorCode());
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/merchant/v1/profile");
        PayRequestContext.setPrincipal(
                request,
                new PayPrincipal(
                        PayPrincipal.Type.SERVICE,
                        "merchant-001",
                        PayPrincipal.ServiceType.MERCHANT_SERVICE));
        return request;
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        return generator.generateKeyPair();
    }

    private ECKey publicKey(KeyPair keyPair, String keyId) {
        return new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .build();
    }
}
