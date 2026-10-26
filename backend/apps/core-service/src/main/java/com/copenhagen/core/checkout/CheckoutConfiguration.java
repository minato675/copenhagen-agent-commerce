package com.copenhagen.core.checkout;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.ucp.CoreUcpProperties;
import com.copenhagen.core.ucp.MerchantProfileDirectory;

/**
 * merchant별 Checkout 클라이언트. {@code services.merchants} 목록을 Catalog(순서 24)와 같이 쓴다 —
 * 같은 merchant의 다른 capability라 설정을 따로 두지 않는다.
 */
@Configuration
public class CheckoutConfiguration {

    @Bean
    public Map<String, MerchantCheckoutClient> merchantCheckoutClients(
            MerchantsProperties properties, MerchantProfileDirectory directory, CoreUcpProperties ucpProperties) {
        Map<String, MerchantCheckoutClient> clients = new LinkedHashMap<>();
        for (MerchantsProperties.MerchantConfig config : properties.merchants()) {
            clients.put(config.id(), new MerchantCheckoutClient(config, directory, ucpProperties.profileUrl()));
        }
        return Map.copyOf(clients);
    }
}
