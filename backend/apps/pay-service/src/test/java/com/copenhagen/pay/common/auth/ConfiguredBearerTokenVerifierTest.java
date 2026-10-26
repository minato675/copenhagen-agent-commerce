package com.copenhagen.pay.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfiguredBearerTokenVerifierTest {

    @Test
    void jsonMappingsCreateUserCoreAndMerchantPrincipals() {
        PayAuthProperties properties = new PayAuthProperties(
                Map.of(),
                Map.of(),
                Map.of(),
                "{\"user-001\":\"user-token\"}",
                "{\"core-service\":\"core-token\"}",
                "{\"merchant-a\":\"merchant-token\"}");
        ConfiguredBearerTokenVerifier verifier = new ConfiguredBearerTokenVerifier(properties);

        PayPrincipal user = verifier.verify("user-token").orElseThrow();
        PayPrincipal core = verifier.verify("core-token").orElseThrow();
        PayPrincipal merchant = verifier.verify("merchant-token").orElseThrow();

        assertEquals(PayPrincipal.Type.USER, user.type());
        assertEquals("user-001", user.subject());
        assertEquals(PayPrincipal.ServiceType.CORE_SERVICE, core.serviceType());
        assertEquals("core-service", core.subject());
        assertEquals(PayPrincipal.ServiceType.MERCHANT_SERVICE, merchant.serviceType());
        assertEquals("merchant-a", merchant.subject());
    }

    @Test
    void duplicateTokensAreRejectedWithoutIncludingTokenValue() {
        String duplicateToken = "same-secret";
        PayAuthProperties properties = new PayAuthProperties(
                Map.of(),
                Map.of(),
                Map.of(),
                "{\"user-001\":\"" + duplicateToken + "\"}",
                "{\"core-service\":\"" + duplicateToken + "\"}",
                "");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new ConfiguredBearerTokenVerifier(properties));

        assertEquals(false, exception.getMessage().contains(duplicateToken));
    }
}
