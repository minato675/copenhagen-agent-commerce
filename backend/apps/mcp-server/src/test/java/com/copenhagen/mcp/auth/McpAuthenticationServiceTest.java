package com.copenhagen.mcp.auth;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpAuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final String ISSUER = "https://auth.example.test";
    private static final String RESOURCE = "https://mcp.example.test/mcp";
    private static final McpAuthProperties PROPERTIES = new McpAuthProperties(
            URI.create(ISSUER), URI.create(RESOURCE), Set.of("mcp:access"));

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "issuer", "audience", "expires", "expiry-boundary", "missing-expiry",
            "not-before", "missing-user", "missing-connection"})
    void authenticate_invalidProviderResult_rejects(String scenario) {
        VerifiedMcpToken claims = claims(scenario);
        assertEquals(McpAuthError.INVALID_TOKEN, failure(service(token -> claims)).error());
    }

    @Test
    void authenticate_notConnected_requestsAccountConnection() {
        assertEquals(McpAuthError.ACCOUNT_NOT_CONNECTED,
                failure(service(token -> claims("disconnected"))).error());
    }

    @Test
    void authenticate_missingScope_requestsRequiredPermission() {
        McpAuthException exception = failure(service(token -> claims("scope")));
        assertEquals(McpAuthError.INSUFFICIENT_SCOPE, exception.error());
        assertEquals(Set.of("mcp:access", "unrelated:read"), exception.requiredScopes());
    }

    @Test
    void authenticate_validResult_returnsOnlyVerifiedIdentity() {
        McpIdentity identity = service(token -> claims("valid")).authenticate(UUID.randomUUID().toString());
        assertEquals("public-user-a", identity.publicUserRef());
        assertEquals("connection-a", identity.connectionRef());
        assertFalse(identity.toString().contains("public-user-a"));
        assertFalse(claims("valid").toString().contains("public-user-a"));
    }

    @Test
    void authenticate_notBeforeBoundary_acceptsAtExactTime() {
        assertEquals("public-user-a",
                service(token -> claims("nbf-boundary")).authenticate(UUID.randomUUID().toString()).publicUserRef());
    }

    @Test
    void authenticate_providerFailure_masksInternalDetails() {
        String privateValue = UUID.randomUUID().toString();
        McpAuthException exception = failure(service(token -> {
            throw new IllegalStateException(privateValue);
        }));
        assertEquals(McpAuthError.AUTH_PROVIDER_UNAVAILABLE, exception.error());
        assertFalse(exception.toString().contains(privateValue));
    }

    @Test
    void authenticate_missingProvider_failsClosed() {
        assertEquals(McpAuthError.AUTH_NOT_CONFIGURED, failure(service(null)).error());
        assertEquals(McpAuthError.INVALID_TOKEN, failure(service(token -> null)).error());
    }

    @Test
    void authenticate_missingConfiguration_doesNotCallProvider() {
        McpAuthenticationService authentication = new McpAuthenticationService(
                new McpAuthProperties(null, null, Set.of()), () -> {
                    throw new AssertionError("Provider must not run without configuration");
                }, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(McpAuthError.AUTH_NOT_CONFIGURED, failure(authentication).error());
    }

    @Test
    void requireOwner_differentOrMissingOwner_deniesAccess() {
        McpIdentity identity = service(token -> claims("valid")).authenticate(UUID.randomUUID().toString());
        identity.requireOwner("public-user-a");
        assertEquals(McpAuthError.ACCESS_DENIED,
                assertThrows(McpAuthException.class, () -> identity.requireOwner("public-user-b")).error());
        assertThrows(McpAuthException.class, () -> identity.requireOwner(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://auth.example.test", "https://user:password@auth.example.test",
            "https://auth.example.test?token=value", "https://auth.example.test/#fragment", "//auth.example.test"})
    void properties_unsafeIssuer_rejectsConfiguration(String issuer) {
        assertThrows(IllegalArgumentException.class,
                () -> new McpAuthProperties(URI.create(issuer), URI.create(RESOURCE), Set.of("mcp:access")));
    }

    @Test
    void properties_scopeHeaderInjection_rejectsConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpAuthProperties(URI.create(ISSUER), URI.create(RESOURCE), Set.of("read\"\r\nX: value")));
    }

    private McpAuthenticationService service(McpTokenVerifier verifier) {
        return new McpAuthenticationService(PROPERTIES, () -> verifier, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private McpAuthException failure(McpAuthenticationService service) {
        return assertThrows(McpAuthException.class, () -> service.authenticate(UUID.randomUUID().toString()));
    }

    private VerifiedMcpToken claims(String scenario) {
        Instant expires = switch (scenario) {
            case "expires" -> NOW.minusSeconds(1);
            case "expiry-boundary" -> NOW;
            case "missing-expiry" -> null;
            default -> NOW.plusSeconds(600);
        };
        Instant notBefore = switch (scenario) {
            case "not-before" -> NOW.plusSeconds(1);
            case "nbf-boundary" -> NOW;
            default -> NOW.minusSeconds(60);
        };
        return new VerifiedMcpToken(!scenario.equals("inactive"),
                scenario.equals("issuer") ? "https://other.example.test" : ISSUER,
                Set.of(scenario.equals("audience") ? "https://pay.example.test" : RESOURCE),
                expires, notBefore, scenario.equals("missing-user") ? "" : "public-user-a",
                scenario.equals("missing-connection") ? "" : "connection-a", !scenario.equals("disconnected"),
                scenario.equals("scope") ? Set.of("unrelated:read") : Set.of("mcp:access"));
    }
}
