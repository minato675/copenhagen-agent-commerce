package com.copenhagen.mcp.merchant;

import com.copenhagen.mcp.auth.McpAuthError;
import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.merchant.CoreMerchantDirectory.Capability;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.CHECKOUT;
import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.REGISTRATIONS;
import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.SEARCH;
import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.capability;
import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.change;
import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.response;
import static com.copenhagen.mcp.merchant.MerchantDirectoryFixture.shop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantDirectoryServiceTest {

    private static final McpIdentity IDENTITY = new McpIdentity("user-a", "connection-a", Set.of("fixture:shops:read"),
            Instant.parse("2100-01-01T00:00:00Z"));

    @Test
    void list_emptyDirectory_returnsEmptyListWithoutInventingShops() {
        assertEquals(Map.of("shops", List.of()), service(response("user-a", "connection-a", List.of())).list(IDENTITY));
    }

    @ParameterizedTest
    @CsvSource({"AVAILABLE,CONNECTED,NONE", "AVAILABLE,NOT_REQUIRED,NONE",
        "CONNECTION_REQUIRED,NOT_CONNECTED,CONNECT_SHOP", "CONNECTION_REQUIRED,EXPIRED,RECONNECT_SHOP",
        "UNSUPPORTED,UNKNOWN,CHOOSE_ANOTHER_SHOP", "UNAVAILABLE,UNKNOWN,RETRY_LATER",
        "PERMISSION_REQUIRED,CONNECTED,REQUEST_PERMISSION", "CAPABILITY_MISMATCH,UNKNOWN,CONTACT_OPERATOR"})
    void list_coreState_preservesStateAndExplainsNextAction(String status, String connection, String action) {
        Map<String, Object> snapshot = response("user-a", "connection-a", List.of(shop("shop-a", status, connection)));
        Map<?, ?> result = first(service(snapshot).list(IDENTITY));
        assertEquals(status, result.get("status"));
        assertEquals(connection, result.get("connectionStatus"));
        assertEquals(action, result.get("nextAction"));
        assertEquals(List.of(), result.get("availableMcpTools"));
    }

    @Test
    void list_declaredButUnverifiedCapability_doesNotAdvertiseImplementation() {
        Map<String, Object> shop = change(shop("shop-a", "AVAILABLE", "CONNECTED"),
                "profileCapabilities", List.of(capability(SEARCH), capability(CHECKOUT)));
        Map<?, ?> result = first(service(response("user-a", "connection-a", List.of(shop))).list(IDENTITY));
        assertEquals(List.of(capability(SEARCH)), result.get("coreCapabilities"));
        assertEquals("AVAILABLE", result.get("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"undeclared", "unimplemented", "wrong-version"})
    void list_falseCapabilityClaim_marksOnlyAffectedShop(String scenario) {
        Map<String, Object> invalid = shop("shop-b", "AVAILABLE", "CONNECTED");
        if (scenario.equals("undeclared")) {
            invalid = change(invalid, "profileCapabilities", List.of());
        } else {
            Map<String, Object> claim = capability(scenario.equals("unimplemented")
                    ? CHECKOUT : new Capability(SEARCH.name(), "2"));
            invalid = change(change(invalid, "profileCapabilities", List.of(claim)),
                    "verifiedCapabilities", List.of(claim));
        }
        List<?> results = (List<?>) service(response("user-a", "connection-a",
                List.of(shop("shop-a", "AVAILABLE", "CONNECTED"), invalid))).list(IDENTITY).get("shops");
        assertEquals("AVAILABLE", ((Map<?, ?>) results.get(0)).get("status"));
        Map<?, ?> rejected = (Map<?, ?>) results.get(1);
        assertEquals("CAPABILITY_MISMATCH", rejected.get("status"));
        assertEquals("CONTACT_OPERATOR", rejected.get("nextAction"));
        assertEquals(List.of(), rejected.get("coreCapabilities"));
    }

    @Test
    void list_unregisteredMerchant_neverAdvertisesSupportOrConnection() {
        Map<?, ?> result = first(service(response("user-a", "connection-a",
                List.of(shop("unregistered", "AVAILABLE", "CONNECTED")))).list(IDENTITY));
        assertEquals("UNSUPPORTED", result.get("status"));
        assertEquals("UNKNOWN", result.get("connectionStatus"));
        assertEquals(List.of(), result.get("coreCapabilities"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"publicUserRef", "connectionRef"})
    void list_otherOwnerOrConnection_rejectsWithoutReturningData(String field) {
        Map<String, Object> response = response("user-a", "connection-a",
                List.of(shop("shop-a", "AVAILABLE", "CONNECTED")));
        McpAuthException error = assertThrows(McpAuthException.class,
                () -> service(change(response, field, "other")).list(IDENTITY));
        assertEquals(McpAuthError.ACCESS_DENIED, error.error());
    }

    @ParameterizedTest
    @CsvSource({"AVAILABLE,UNKNOWN", "AVAILABLE,NOT_CONNECTED", "AVAILABLE,EXPIRED",
        "CONNECTION_REQUIRED,CONNECTED", "CONNECTION_REQUIRED,UNKNOWN", "CONNECTION_REQUIRED,NOT_REQUIRED"})
    void list_contradictoryConnectionState_rejectsCoreResponse(String status, String connection) {
        assertInvalid(response("user-a", "connection-a", List.of(shop("shop-a", status, connection))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://untrusted.example", "../shop-a", "shop-a/redirect", "shop-a?url=external", "shop-a\n"})
    void list_urlOrPathAsMerchantId_rejectsCoreResponse(String id) {
        assertInvalid(response("user-a", "connection-a", List.of(shop(id, "AVAILABLE", "CONNECTED"))));
    }

    @Test
    void list_duplicateMerchantOrOversizedDirectory_rejectsInsteadOfTruncating() {
        Map<String, Object> connected = shop("shop-a", "AVAILABLE", "CONNECTED");
        assertInvalid(response("user-a", "connection-a", List.of(connected,
                change(connected, "displayName", "다른 이름"))));
        List<Map<String, Object>> oversized = new ArrayList<>();
        for (int index = 0; index <= MerchantSchemas.MAX_SHOPS; index++) {
            oversized.add(shop("shop-" + index, "AVAILABLE", "CONNECTED"));
        }
        assertInvalid(response("user-a", "connection-a", oversized));
    }

    @Test
    void list_invalidShapeOrUnexpectedEndpoint_rejectsPrivateFields() {
        assertInvalid(null);
        assertInvalid(Map.of());
        assertInvalid(response("user-a", "connection-a", List.of(
                change(shop("shop-a", "AVAILABLE", "CONNECTED"), "endpoint", "http://127.0.0.1/private"))));
        assertInvalid(response("user-a", "connection-a", List.of(
                change(shop("shop-a", "AVAILABLE", "CONNECTED"), "status", "INVENTED"))));
        assertInvalid(response("user-a", "connection-a", List.of(
                change(shop("shop-a", "AVAILABLE", "CONNECTED"),
                        "verifiedCapabilities", List.of(Map.of("name", "x"))))));
    }

    @Test
    void list_connectionChanges_requeriesCoreWithoutReusingPreviousUserState() {
        AtomicReference<Map<String, Object>> latest = new AtomicReference<>(response("user-a", "connection-a",
                List.of(shop("shop-a", "AVAILABLE", "CONNECTED"))));
        MerchantDirectoryService service = new MerchantDirectoryService(directory(latest),
                McpJsonDefaults.getSchemaValidator());
        assertEquals("AVAILABLE", first(service.list(IDENTITY)).get("status"));
        latest.set(response("user-a", "connection-a", List.of(shop("shop-a", "CONNECTION_REQUIRED", "EXPIRED"))));
        assertEquals("RECONNECT_SHOP", first(service.list(IDENTITY)).get("nextAction"));
    }

    @Test
    void list_success_stripsIdentityAndUnverifiedProfileAndMatchesOutputSchema() {
        Map<String, Object> result = service(response("user-a", "connection-a",
                List.of(shop("shop-a", "AVAILABLE", "CONNECTED")))).list(IDENTITY);
        assertTrue(McpJsonDefaults.getSchemaValidator().validate(MerchantSchemas.OUTPUT, result).valid());
        assertEquals(Set.of("shops"), result.keySet());
        assertFalse(first(result).containsKey("profileCapabilities"));
    }

    private MerchantDirectoryService service(Map<String, Object> response) {
        return new MerchantDirectoryService(directory(new AtomicReference<>(response)),
                McpJsonDefaults.getSchemaValidator());
    }

    private CoreMerchantDirectory directory(AtomicReference<Map<String, Object>> response) {
        return new CoreMerchantDirectory() {
            @Override
            public Map<String, Set<Capability>> registeredCapabilities() {
                return REGISTRATIONS;
            }

            @Override
            public Map<String, Object> list(McpIdentity identity) {
                assertEquals(IDENTITY, identity);
                return response.get();
            }
        };
    }

    private Map<?, ?> first(Map<String, Object> result) {
        return (Map<?, ?>) ((List<?>) result.get("shops")).getFirst();
    }

    private void assertInvalid(Map<String, Object> response) {
        CoreClientException error = assertThrows(CoreClientException.class, () -> service(response).list(IDENTITY));
        assertEquals(ToolErrorCode.CORE_INVALID_RESPONSE, error.code());
    }
}
