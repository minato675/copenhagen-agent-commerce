package com.copenhagen.core.product;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code services.merchants} 목록. 다중 merchant를 순회하도록 리스트로 둔다(현재는 1개만 배포돼 있어도 됨).
 * 객체 리스트는 {@code @Value}로 바인딩할 수 없어 core-service에서 이번에 처음
 * {@code @ConfigurationProperties}를 예외적으로 사용한다.
 */
@ConfigurationProperties("services")
public record MerchantsProperties(List<MerchantConfig> merchants) {

    /**
     * {@code refId}는 {@code merchants.id}(BIGINT)다. {@code cart_items.merchant_id}가 이 테이블의 FK라
     * 장바구니가 설정의 문자열 {@code id}를 DB 행으로 바꿀 키가 필요하다(순서 25). 시드는
     * {@code V7__seed_merchants.sql}이 고정 PK로 넣는다.
     */
    public record MerchantConfig(
            String id,
            Long refId,
            String displayName,
            URI baseUrl,
            Duration connectTimeout,
            Duration requestTimeout) {
    }
}
