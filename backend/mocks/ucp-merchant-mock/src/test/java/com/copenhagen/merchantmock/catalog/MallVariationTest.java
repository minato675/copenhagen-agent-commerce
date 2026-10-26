package com.copenhagen.merchantmock.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 몰별 편차 규칙의 Java 구현이 파이썬 원본({@code tools/mall_rules.py})과 같은 답을 내는지 본다(DESIGN.md 5.7절).
 *
 * <p>황금값은 {@code tools/mall_variation_golden.py} 가 뽑아 {@code catalog-pool/golden.json} 에 고정했다.
 * 규칙을 바꾸면 두 구현을 같이 바꾸고 황금값을 다시 뽑는다. 여기서 틀리면 몰 셋의 가격이 리허설과 달라진다.
 */
class MallVariationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MallVariation variation = new MallVariation(MAPPER);

    private static JsonNode golden() throws IOException {
        try (InputStream input = new ClassPathResource("catalog-pool/golden.json").getInputStream()) {
            return MAPPER.readTree(input);
        }
    }

    @Test
    @DisplayName("해시 원시값이 파이썬과 같다 — u, 배율, uuid5")
    void primitives_matchPythonReference() throws IOException {
        // given
        JsonNode primitives = golden().path("primitives");
        String externalId = primitives.path("externalId").asString();
        String mall = primitives.path("mall").asString();

        // when & then: 부동소수점을 그대로 비교한다. 두 구현이 같은 연산 순서로 같은 double 을 내야 한다
        assertEquals(primitives.path("uPrice").asDouble(), MallVariation.u(externalId, mall, "price"));
        assertEquals(primitives.path("uStock").asDouble(), MallVariation.u(externalId, mall, "stock"));
        assertEquals(primitives.path("factor").asDouble(), MallVariation.priceFactor(externalId, mall));
        assertEquals(primitives.path("id").asString(), MallVariation.deterministicId(mall, externalId));
    }

    @Test
    @DisplayName("풀 상품을 몰 셋으로 전개한 id·가격·옵션·재고가 황금값과 같다")
    void derive_goldenCases_matchPython() throws IOException {
        // given
        JsonNode cases = golden().path("cases");
        assertTrue(cases.size() >= 8, "황금값이 너무 적다: " + cases.size());
        int checked = 0;

        for (JsonNode expected : cases) {
            CatalogPool.PoolProduct product = poolProduct(expected);
            for (String mall : List.of("merchant-a", "merchant-b", "merchant-c")) {
                JsonNode want = expected.path("malls").path(mall);

                // when
                CatalogSeed.Entry entry = variation.derive(mall, product);

                // then
                String label = product.externalId() + "@" + mall;
                assertEquals(want.path("id").asString(), entry.id(), label + " id");
                assertEquals(want.path("basePrice").asLong(), entry.basePrice(), label + " basePrice");
                assertEquals(mall, entry.merchantId());
                assertEquals(product.externalId(), entry.externalId());
                JsonNode variants = MAPPER.readTree(entry.variantsJson());
                assertEquals(want.path("variants").size(), variants.size(), label + " 옵션 수");
                for (int i = 0; i < variants.size(); i++) {
                    JsonNode wantVariant = want.path("variants").get(i);
                    JsonNode gotVariant = variants.get(i);
                    assertEquals(wantVariant.path("sku").asString(), gotVariant.path("sku").asString(), label);
                    assertEquals(wantVariant.path("priceDelta").asLong(), gotVariant.path("priceDelta").asLong(),
                            label + " " + gotVariant.path("sku").asString() + " priceDelta");
                    assertEquals(wantVariant.path("stock").asInt(), gotVariant.path("stock").asInt(),
                            label + " " + gotVariant.path("sku").asString() + " stock");
                }
                checked++;
            }
        }
        assertTrue(checked >= 24, "비교한 (상품, 몰) 쌍 " + checked);
    }

    @Test
    @DisplayName("몰이 다르면 id 가 다르고 가격은 원본의 ±10% 안에서 갈린다")
    void derive_threeMalls_differentIdsAndPricesWithinBand() {
        // given
        CatalogPool.PoolProduct product = new CatalogPool.PoolProduct(
                "kurly:1000017436", "샴푸", "뷰티 > 샴푸", "미쟝센", "https://img/x.jpg", 8700, "KRW", "ACTIVE",
                List.of(new CatalogPool.PoolVariant("kurly:1000017436-default", "option", "기본", 0, 100, "ACTIVE")));

        // when
        CatalogSeed.Entry a = variation.derive("merchant-a", product);
        CatalogSeed.Entry b = variation.derive("merchant-b", product);
        CatalogSeed.Entry c = variation.derive("merchant-c", product);

        // then
        assertFalse(a.id().equals(b.id()) || b.id().equals(c.id()) || a.id().equals(c.id()), "id 가 겹친다");
        for (CatalogSeed.Entry entry : List.of(a, b, c)) {
            assertTrue(entry.basePrice() >= 8700 * 0.90 - 10 && entry.basePrice() <= 8700 * 1.10 + 10,
                    entry.merchantId() + " " + entry.basePrice());
            assertEquals(0, entry.basePrice() % 10, "1만원 미만은 10원 단위");
        }
    }

    @Test
    @DisplayName("반올림은 파이썬 int(x + 0.5) 와 같이 0 쪽으로 자른다")
    void roundTo_matchesPythonTruncation() {
        assertEquals(8700, MallVariation.roundTo(8695.0, 10));
        assertEquals(8690, MallVariation.roundTo(8694.9, 10));
        assertEquals(273800, MallVariation.roundTo(273750.0, 100));
        assertEquals(10, MallVariation.roundingUnit(9999));
        assertEquals(100, MallVariation.roundingUnit(10000));
    }

    private static CatalogPool.PoolProduct poolProduct(JsonNode node) {
        List<CatalogPool.PoolVariant> variants = new ArrayList<>();
        for (JsonNode variant : node.path("variants")) {
            variants.add(new CatalogPool.PoolVariant(
                    variant.path("sku").asString(), variant.path("optionName").asString(),
                    variant.path("optionValue").asString(), variant.path("priceDelta").asLong(),
                    variant.path("stock").asInt(), variant.path("status").asString("ACTIVE")));
        }
        return new CatalogPool.PoolProduct(
                node.path("externalId").asString(), "제목", "카테고리", "", "https://img/x.jpg",
                node.path("basePrice").asLong(), "KRW", "ACTIVE", variants);
    }
}
