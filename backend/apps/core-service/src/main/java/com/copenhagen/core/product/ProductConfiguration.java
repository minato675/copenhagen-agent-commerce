package com.copenhagen.core.product;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.copenhagen.core.ucp.CoreUcpProperties;
import com.copenhagen.core.ucp.MerchantProfileDirectory;

@Configuration
@EnableConfigurationProperties({MerchantsProperties.class, CoreUcpProperties.class})
public class ProductConfiguration {

    @Bean(destroyMethod = "close")
    CatalogSearchFanout catalogSearchFanout(
            @Value("${catalog-search.concurrency}") int concurrency,
            @Value("${catalog-search.queue-capacity}") int queueCapacity,
            @Value("${catalog-search.budget}") Duration budget) {
        return new CatalogSearchFanout(concurrency, queueCapacity, budget);
    }

    @Bean
    public Map<String, MerchantCatalogClient> merchantCatalogClients(
            MerchantsProperties properties, MerchantProfileDirectory directory, CoreUcpProperties ucpProperties) {
        Map<String, MerchantCatalogClient> clients = new LinkedHashMap<>();
        for (MerchantsProperties.MerchantConfig config : properties.merchants()) {
            clients.put(config.id(), new MerchantCatalogClient(
                    config, directory, ucpProperties.profileUrl()));
        }
        return Map.copyOf(clients);
    }
}
