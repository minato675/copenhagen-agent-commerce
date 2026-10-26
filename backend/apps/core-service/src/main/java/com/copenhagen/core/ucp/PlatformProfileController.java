package com.copenhagen.core.ucp;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Merchant가 조회하는 Core UCP Platform Profile. */
@RestController
public class PlatformProfileController {

    private static final List<String> CAPABILITIES = List.of(
            "dev.ucp.shopping.catalog.search",
            "dev.ucp.shopping.catalog.lookup",
            "dev.ucp.shopping.checkout",
            "dev.ucp.shopping.order",
            "dev.ucp.shopping.fulfillment");

    private final CoreUcpProperties properties;

    public PlatformProfileController(CoreUcpProperties properties) {
        this.properties = properties;
    }

    @GetMapping(path = CoreUcpProperties.PROFILE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> profile() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        for (String capability : CAPABILITIES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", CoreUcpProperties.UCP_VERSION);
            if (capability.endsWith(".order")) {
                entry.put("config", Map.of("webhook_url", properties.orderWebhookUrl()));
            }
            capabilities.put(capability, List.of(entry));
        }
        return Map.of("ucp", Map.of(
                "version", CoreUcpProperties.UCP_VERSION,
                "capabilities", capabilities), "keys", List.of());
    }
}
