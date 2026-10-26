package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.List;

/** V3__seed_products.sql 과 같은 모양의 시드. 테스트가 DB 없이 돌게 한다. */
public final class CatalogFixture {

    private CatalogFixture() {
    }

    public static List<Product> seed() {
        return List.of(
                product("product-101", "러닝화 A", "스포츠/레저 > 스포츠화", "나이키",
                        89000, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-101-260", "size", "260", 0, 12, Product.ProductStatus.ACTIVE),
                        variant("product-101-270", "size", "270", 0, 7, Product.ProductStatus.ACTIVE),
                        variant("product-101-280", "size", "280", 0, 0, Product.ProductStatus.ACTIVE))),
                product("product-102", "경량 바람막이", "패션/잡화 > 의류", "아디다스",
                        129000, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-102-m-black", "size", "M", 0, 20, Product.ProductStatus.ACTIVE),
                        variant("product-102-l-black", "size", "L", 0, 15, Product.ProductStatus.ACTIVE),
                        variant("product-102-xl-black", "size", "XL", 5000, 4, Product.ProductStatus.ACTIVE))),
                product("product-103", "무선 이어폰", "디지털 완제품 > 이어폰", "소니",
                        219000, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-103-white", "color", "화이트", 0, 30, Product.ProductStatus.ACTIVE),
                        variant("product-103-black", "color", "블랙", 0, 25, Product.ProductStatus.ACTIVE))),
                product("product-104", "보온 텀블러 500ml", "주방용품 > 컵/텀블러", null,
                        27000, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-104-single", "type", "기본", 0, 100, Product.ProductStatus.ACTIVE))),
                product("product-105", "단종된 백팩", "패션/잡화 > 가방/지갑", null,
                        74000, Product.ProductStatus.DISCONTINUED, List.of(
                        variant("product-105-navy", "color", "네이비", 0, 0,
                                Product.ProductStatus.DISCONTINUED))));
    }

    /** 모든 옵션이 품절인 상품. 시나리오 OUT_OF_STOCK 을 흉내 낸다. */
    public static Product soldOut() {
        return product("product-199", "품절된 러닝화", "스포츠/레저 > 스포츠화", "아디다스",
                99000, Product.ProductStatus.ACTIVE, List.of(
                variant("product-199-260", "size", "260", 0, 0, Product.ProductStatus.ACTIVE)));
    }

    /** 제목에는 검색어가 없고 카테고리로만 걸리는 상품. 관련도 정렬을 시험한다(470). */
    public static Product categoryOnlyMatch() {
        return product("product-201", "트레일 슈즈 X", "스포츠/레저 > 운동화", null,
                119000, Product.ProductStatus.ACTIVE, List.of(
                variant("product-201-270", "size", "270", 0, 5, Product.ProductStatus.ACTIVE)));
    }

    /** 제목 낱말로 맞은 것과 부분문자열로만 맞은 것. "우유" 오탐 순서를 시험한다(470). */
    public static List<Product> milkAndLookalikes() {
        return List.of(
                product("product-301", "우유팩 모양 키링", "문구/팬시 > 기타", null,
                        4900, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-301-single", "type", "기본", 0, 10, Product.ProductStatus.ACTIVE))),
                product("product-302", "제주목장 우유 900ml", "유제품 > 우유 > 흰우유", null,
                        3500, Product.ProductStatus.ACTIVE, List.of(
                        variant("product-302-single", "type", "기본", 0, 10, Product.ProductStatus.ACTIVE))));
    }

    /** 제목·카테고리만 있는 판매 중 상품 하나. 검색 순서 시험(6.7절)이 쓴다. */
    public static Product simple(String externalId, String title, String category) {
        return product(externalId, title, category, null, 5000, Product.ProductStatus.ACTIVE, List.of(
                variant(externalId + "-single", "option", "기본", 0, 10, Product.ProductStatus.ACTIVE)));
    }

    /** 상한 시험용. 검색어가 전부 걸리도록 같은 낱말을 제목에 둔다(470). */
    public static List<Product> many(int count) {
        List<Product> products = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String externalId = "product-bulk-" + index;
            products.add(product(externalId, "대량 상품 " + index, "테스트 > 대량", null,
                    1000 + index, Product.ProductStatus.ACTIVE, List.of(
                    variant(externalId + "-single", "type", "기본", 0, 10, Product.ProductStatus.ACTIVE))));
        }
        return List.copyOf(products);
    }

    private static Product product(
            String externalId, String title, String category, String brand, long basePrice,
            Product.ProductStatus status, List<Product.Variant> variants) {
        return new Product(
                externalId + "-uuid", "merchant-a", externalId, title, null, category, brand,
                "https://cdn.example.test/" + externalId + ".jpg",
                basePrice, "KRW", status, variants);
    }

    private static Product.Variant variant(
            String sku, String optionName, String optionValue,
            long priceDelta, int stock, Product.ProductStatus status) {
        return new Product.Variant(sku, optionName, optionValue, priceDelta, stock, status);
    }
}
