package com.copenhagen.merchantmock.catalog;

import java.util.List;

import com.copenhagen.contract.ucp.CatalogOptionValue;


import com.fasterxml.jackson.annotation.JsonInclude;

/** Catalog 세 엔드포인트의 요청·응답. 필드 표기는 Core 확정본을 따라 camelCase 다. */
public final class CatalogMessages {

    private CatalogMessages() {
    }

    public record SearchRequest(String query, Filters filters) {

        public record Filters(Long maxPrice, String currency) {
        }
    }

    public record LookupRequest(List<String> productIds) {
    }

    public record ProductRequest(String productId) {
    }

    /**
     * 연관 상품 요청(DESIGN.md 6.8절, ADR 0014). 사용자 식별자는 받지 않는다 — 개인화는 부르는 쪽이 구매이력의 상품 id 를 넣어 조립한다.
     * 앞쪽 id 가 우선이고 여러 개면 번갈아 꺼낸다.
     */
    public record RelatedRequest(List<String> productIds, Integer limit) {
    }

    /** 검색과 일괄 조회가 같이 쓴다. 결과가 없으면 빈 배열이고 404 를 내지 않는다. */
    public record ItemsResponse(List<Item> items) {
    }

    /**
     * 검색·일괄 조회의 항목. {@code imageUrl} 은 2026-09-18 에 추가했다(428).
     *
     * <p>그전에는 내 응답에 이미지가 아예 없었다. {@code products.image_url} 은 채워져 있는데 싣지 않아
     * Agent 와 앱이 상품 그림을 못 받았다. 필드 추가라 옛 소비자는 그대로 돈다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String productId, String title, String imageUrl, Price price, String availability) {

        static Item of(Product product) {
            return new Item(
                    product.externalId(),
                    product.title(),
                    product.imageUrl(),
                    new Price(product.displayPrice(), product.currency()),
                    product.availability());
        }
    }

    public record Price(long amount, String currency) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductDetail(
            String productId,
            String title,
            String imageUrl,
            String description,
            Price price,
            List<Option> options,
            String availability) {

        static ProductDetail of(Product product) {
            return new ProductDetail(
                    product.externalId(),
                    product.title(),
                    product.imageUrl(),
                    product.description(),
                    new Price(product.displayPrice(), product.currency()),
                    Option.from(product),
                    product.availability());
        }
    }

    /**
     * 옵션 묶음. 같은 optionName 끼리 모은다.
     *
     * <p>구매 가능한 옵션의 단가와 재고를 함께 전달한다. 최종 거래 조건은 Checkout에서 다시 검증한다.
     */
    public record Option(String name, List<CatalogOptionValue> values) {

        static List<Option> from(Product product) {
            return product.variants().stream()
                    .filter(Product.Variant::purchasable)
                    .collect(java.util.stream.Collectors.groupingBy(
                            Product.Variant::optionName,
                            java.util.LinkedHashMap::new,
                            java.util.stream.Collectors.toList()))
                    .entrySet().stream()
                    .map(entry -> new Option(
                            entry.getKey(),
                            entry.getValue().stream()
                                    .map(v -> new CatalogOptionValue(v.sku(), v.optionValue(),
                                            Math.addExact(product.basePrice(), v.priceDelta()), v.stock()))
                                    .toList()))
                    .toList();
        }
    }

}
