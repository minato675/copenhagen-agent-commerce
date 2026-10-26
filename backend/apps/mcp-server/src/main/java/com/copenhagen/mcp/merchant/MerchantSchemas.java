package com.copenhagen.mcp.merchant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core wire 계약이 아닌 MCP 내부 정규화 형식과 공개 Tool 형식. */
public final class MerchantSchemas {

    public static final int MAX_SHOPS = 100;
    public static final String MERCHANT_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}";
    public static final Map<String, Object> INPUT = object(Map.of());
    public static final Map<String, Object> CAPABILITY = object(Map.of(
            "name", identifier("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"),
            "version", identifier("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")));
    public static final Map<String, Object> CORE_RESPONSE = object(Map.of(
            "publicUserRef", text(256), "connectionRef", text(256),
            "shops", array(coreShop(), MAX_SHOPS)));
    public static final Map<String, Object> OUTPUT = object(Map.of("shops", array(outputShop(), MAX_SHOPS)));

    private MerchantSchemas() {
    }

    private static Map<String, Object> coreShop() {
        Map<String, Object> properties = shopProperties();
        properties.put("profileCapabilities", array(CAPABILITY, 32));
        properties.put("verifiedCapabilities", array(CAPABILITY, 32));
        return object(properties);
    }

    private static Map<String, Object> outputShop() {
        Map<String, Object> properties = shopProperties();
        properties.put("nextAction", enumeration("NONE", "CONNECT_SHOP", "RECONNECT_SHOP", "CHOOSE_ANOTHER_SHOP",
                "RETRY_LATER", "REQUEST_PERMISSION", "CONTACT_OPERATOR"));
        properties.put("coreCapabilities", array(CAPABILITY, 32));
        // 이 Task는 목록 조회만 구현한다. 후속 구매 Tool이 등록되기 전 호출 가능으로 광고하지 않는다.
        properties.put("availableMcpTools", Map.of("type", "array", "maxItems", 0,
                "items", Map.of("type", "string")));
        return object(properties);
    }

    private static Map<String, Object> shopProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("merchantId", identifier(MERCHANT_ID_PATTERN));
        properties.put("displayName", text(120));
        properties.put("connectionStatus", enumeration("CONNECTED", "NOT_CONNECTED", "EXPIRED", "NOT_REQUIRED",
                "UNKNOWN"));
        properties.put("status", enumeration("AVAILABLE", "CONNECTION_REQUIRED", "UNSUPPORTED", "UNAVAILABLE",
                "PERMISSION_REQUIRED", "CAPABILITY_MISMATCH"));
        return properties;
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", Map.copyOf(properties),
                "required", properties.keySet().stream().sorted().toList(), "additionalProperties", false);
    }

    private static Map<String, Object> array(Map<String, Object> item, int maximum) {
        return Map.of("type", "array", "items", item, "maxItems", maximum, "uniqueItems", true);
    }

    private static Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum, "pattern", "\\S");
    }

    private static Map<String, Object> identifier(String pattern) {
        return Map.of("type", "string", "pattern", "^" + pattern + "$");
    }

    private static Map<String, Object> enumeration(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
