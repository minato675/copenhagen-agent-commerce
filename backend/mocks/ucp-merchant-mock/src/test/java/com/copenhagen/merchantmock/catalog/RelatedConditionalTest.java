package com.copenhagen.merchantmock.catalog;

import java.util.List;

import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 롤백 사다리 1단(DESIGN.md 6.8.1절). 기본값이면 연관 상품 빈이 아예 없어야 한다 — 색인·이웃 파일을 읽지 않고 경로도 없다.
 */
class RelatedConditionalTest {

    @Configuration
    @EnableConfigurationProperties(RelatedProperties.class)
    static class Dependencies {

        @Bean
        CatalogStore catalogStore() {
            CatalogStore store = new CatalogStore(CatalogFixture::seed);
            store.load();
            return store;
        }

        @Bean
        MerchantProperties merchantProperties() {
            return MerchantPropertiesFixture.defaults();
        }

        @Bean
        CatalogService catalogService(CatalogStore store, MerchantProperties merchants) {
            return new CatalogService(store, merchants);
        }

        @Bean
        CatalogPoolReader catalogPoolReader() {
            return new CatalogPoolReader(new DefaultResourceLoader(), new ObjectMapper());
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Dependencies.class, RelatedService.class, RelatedController.class);

    @Test
    @DisplayName("설정이 없으면(기본값) 연관 상품 서비스·컨트롤러 빈이 없다")
    void context_default_hasNoRelatedBeans() {
        runner.run(context -> {
            assertTrue(context.getBeansOfType(RelatedService.class).isEmpty());
            assertTrue(context.getBeansOfType(RelatedController.class).isEmpty());
        });
    }

    @Test
    @DisplayName("enabled=false 면 빈이 없고, true 면 서비스·컨트롤러가 하나씩 생긴다")
    void context_enabledSwitch_controlsBeans() {
        runner.withPropertyValues("merchant.catalog.related.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(RelatedService.class).isEmpty()));
        runner.withPropertyValues("merchant.catalog.related.enabled=true", "merchant.catalog.related.mode=rrf2")
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(RelatedService.class).size());
                    assertEquals(1, context.getBeansOfType(RelatedController.class).size());
                    assertEquals(List.of("rrf2"),
                            List.of(context.getBean(RelatedProperties.class).requestedMode().value()));
                });
    }
}
