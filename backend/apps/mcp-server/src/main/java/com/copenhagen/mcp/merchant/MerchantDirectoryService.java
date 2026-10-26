package com.copenhagen.mcp.merchant;

import com.copenhagen.mcp.auth.McpAuthError;
import com.copenhagen.mcp.auth.McpAuthException;
import com.copenhagen.mcp.auth.McpIdentity;
import com.copenhagen.mcp.core.CoreClientException;
import com.copenhagen.mcp.merchant.CoreMerchantDirectory.Capability;
import com.copenhagen.mcp.tool.ToolErrorCode;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Core의 판정을 검증·표현하는 소비자 경계. 연결 상태 변경이나 Merchant 호출을 수행하지 않는다. */
public class MerchantDirectoryService {

    private final CoreMerchantDirectory directory;
    private final JsonSchemaValidator validator;
    private final Map<String, Set<Capability>> registered;

    public MerchantDirectoryService(CoreMerchantDirectory directory, JsonSchemaValidator validator) {
        this.directory = directory;
        this.validator = validator;
        Map<String, Set<Capability>> registrations = new HashMap<>();
        directory.registeredCapabilities().forEach((id, capabilities) -> {
            if (!id.matches(MerchantSchemas.MERCHANT_ID_PATTERN)) {
                throw new IllegalArgumentException("Registered merchant must have a bounded public identifier");
            }
            registrations.put(id, Set.copyOf(capabilities));
        });
        registered = Map.copyOf(registrations);
    }

    public Map<String, Object> list(McpIdentity identity) {
        Map<String, Object> response = directory.list(identity);
        if (response == null || !validator.validate(MerchantSchemas.CORE_RESPONSE, response).valid()) {
            throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE);
        }
        identity.requireOwner((String) response.get("publicUserRef"));
        if (!identity.connectionRef().equals(response.get("connectionRef"))) {
            throw new McpAuthException(McpAuthError.ACCESS_DENIED);
        }
        List<Map<String, Object>> shops = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object value : (List<?>) response.get("shops")) {
            Map<?, ?> shop = (Map<?, ?>) value;
            String id = (String) shop.get("merchantId");
            if (!id.matches(MerchantSchemas.MERCHANT_ID_PATTERN) || !seen.add(id)) {
                throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE);
            }
            shops.add(present(shop, id));
        }
        return Map.of("shops", List.copyOf(shops));
    }

    private Map<String, Object> present(Map<?, ?> shop, String id) {
        String connection = (String) shop.get("connectionStatus");
        String status = (String) shop.get("status");
        Set<Capability> declared = capabilities(shop.get("profileCapabilities"));
        Set<Capability> verified = capabilities(shop.get("verifiedCapabilities"));
        if (!registered.containsKey(id)) {
            status = "UNSUPPORTED";
            connection = "UNKNOWN";
        } else if (!declared.containsAll(verified) || !registered.get(id).containsAll(verified)) {
            // Profile 선언만으로 구현·계약 지원을 확정하지 않는다. 다른 판매처의 정상 결과는 보존한다.
            status = "CAPABILITY_MISMATCH";
        }
        validateState(status, connection);
        List<Map<String, Object>> supported = status.equals("UNSUPPORTED") || status.equals("CAPABILITY_MISMATCH")
                ? List.of() : verified.stream().sorted(Comparator.comparing(Capability::name)
                        .thenComparing(Capability::version))
                        .map(capability -> Map.<String, Object>of("name", capability.name(),
                                "version", capability.version())).toList();
        return Map.of("merchantId", id, "displayName", shop.get("displayName"),
                "connectionStatus", connection, "status", status, "nextAction", nextAction(status, connection),
                "coreCapabilities", supported, "availableMcpTools", List.of());
    }

    private Set<Capability> capabilities(Object value) {
        Set<Capability> capabilities = new HashSet<>();
        for (Object item : (List<?>) value) {
            Map<?, ?> capability = (Map<?, ?>) item;
            try {
                capabilities.add(new Capability((String) capability.get("name"), (String) capability.get("version")));
            } catch (IllegalArgumentException exception) {
                throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE, exception);
            }
        }
        return Set.copyOf(capabilities);
    }

    private void validateState(String status, String connection) {
        if ((status.equals("AVAILABLE") && !Set.of("CONNECTED", "NOT_REQUIRED").contains(connection))
                || (status.equals("CONNECTION_REQUIRED") && !Set.of("NOT_CONNECTED", "EXPIRED").contains(connection))) {
            throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE);
        }
    }

    private String nextAction(String status, String connection) {
        return switch (status) {
            case "AVAILABLE" -> "NONE";
            case "CONNECTION_REQUIRED" -> connection.equals("EXPIRED") ? "RECONNECT_SHOP" : "CONNECT_SHOP";
            case "UNSUPPORTED" -> "CHOOSE_ANOTHER_SHOP";
            case "UNAVAILABLE" -> "RETRY_LATER";
            case "PERMISSION_REQUIRED" -> "REQUEST_PERMISSION";
            case "CAPABILITY_MISMATCH" -> "CONTACT_OPERATOR";
            default -> throw new CoreClientException(ToolErrorCode.CORE_INVALID_RESPONSE);
        };
    }
}
