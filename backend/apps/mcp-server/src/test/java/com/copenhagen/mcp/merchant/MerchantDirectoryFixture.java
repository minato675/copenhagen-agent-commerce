package com.copenhagen.mcp.merchant;

import com.copenhagen.mcp.merchant.CoreMerchantDirectory.Capability;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 판매처·capability 이름과 버전은 테스트용이며 실제 Core 계약이나 구현 완료 증거가 아니다. */
public final class MerchantDirectoryFixture {

    public static final Capability SEARCH = new Capability("fixture.catalog.search", "1");
    public static final Capability CHECKOUT = new Capability("fixture.checkout", "1");
    public static final Map<String, Set<Capability>> REGISTRATIONS = Map.of(
            "shop-a", Set.of(SEARCH), "shop-b", Set.of(SEARCH), "shop-c", Set.of(SEARCH));

    private MerchantDirectoryFixture() {
    }

    public static Map<String, Object> response(String user, String connection, List<Map<String, Object>> shops) {
        return Map.of("publicUserRef", user, "connectionRef", connection, "shops", shops);
    }

    public static Map<String, Object> shop(String id, String status, String connection) {
        return Map.of("merchantId", id, "displayName", "테스트 쇼핑몰 " + id,
                "connectionStatus", connection, "status", status,
                "profileCapabilities", List.of(capability(SEARCH)),
                "verifiedCapabilities", List.of(capability(SEARCH)));
    }

    public static Map<String, Object> capability(Capability capability) {
        return Map.of("name", capability.name(), "version", capability.version());
    }

    public static Map<String, Object> change(Map<String, Object> source, String field, Object value) {
        Map<String, Object> changed = new HashMap<>(source);
        changed.put(field, value);
        return Map.copyOf(changed);
    }
}
