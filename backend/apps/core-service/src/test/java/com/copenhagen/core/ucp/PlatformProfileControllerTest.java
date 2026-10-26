package com.copenhagen.core.ucp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformProfileControllerTest {

    @Test
    @DisplayName("Merchant Mock과 같은 UCP 버전과 v1 Order Webhook URL을 광고한다")
    @SuppressWarnings("unchecked")
    void profile_returnsNegotiationContract() {
        PlatformProfileController controller = new PlatformProfileController(
                new CoreUcpProperties("https://core.example/", Duration.ofMinutes(5)));

        Map<String, Object> profile = controller.profile();
        Map<String, Object> ucp = (Map<String, Object>) profile.get("ucp");
        Map<String, List<Map<String, Object>>> capabilities =
                (Map<String, List<Map<String, Object>>>) ucp.get("capabilities");

        assertThat(ucp.get("version")).isEqualTo("2026-08-25");
        assertThat(capabilities).containsKeys(
                "dev.ucp.shopping.catalog.search",
                "dev.ucp.shopping.catalog.lookup",
                "dev.ucp.shopping.checkout",
                "dev.ucp.shopping.order",
                "dev.ucp.shopping.fulfillment");
        assertThat(capabilities.get("dev.ucp.shopping.order").getFirst().get("config"))
                .isEqualTo(Map.of("webhook_url", "https://core.example/webhooks/v1/ucp/orders"));
        assertThat(profile).doesNotContainKey("services");
    }
}
