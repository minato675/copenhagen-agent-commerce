package com.copenhagen.merchantmock.catalog;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import java.util.ArrayList;
import java.util.List;

import com.copenhagen.contract.ucp.CatalogOptionValue;


import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog Search·Lookup·Product 단위 테스트. DB 없이 시드 목록만으로 돌린다. */
class CatalogServiceTest {

    @Test
    void productDetail_includesEffectiveVariantPriceAndStock() {
        Product product = new Product("sample", "merchant-a", "kurly:1000025270", "상품", "설명", null, null, null,
                10500L, "KRW", Product.ProductStatus.ACTIVE, List.of(new Product.Variant(
                        "kurly:1000025270-M00000008360", "종류", "추가 구성", 9100L, 100, Product.ProductStatus.ACTIVE)));
        CatalogOptionValue option = CatalogMessages.ProductDetail.of(product).options().get(0).values().get(0);
        assertEquals(19600L, option.priceAmount());
        assertEquals(100, option.stockQuantity());
    }


    private CatalogService service;

    private static CatalogService serviceOf(List<Product> products) {
        CatalogStore store = new CatalogStore(() -> products);
        store.load();
        return new CatalogService(store, MerchantPropertiesFixture.defaults());
    }

    private static List<String> idsOf(CatalogMessages.ItemsResponse response) {
        List<String> ids = new ArrayList<>();
        response.items().forEach(item -> ids.add(item.productId()));
        return ids;
    }

    @BeforeEach
    void setUp() {
        this.service = serviceOf(CatalogFixture.seed());
    }

    @Test
    @DisplayName("검색과 상세 응답에 상품 이미지가 실린다")
    void search_and_product_includeImageUrl() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search("merchant-a",
                new CatalogMessages.SearchRequest("러닝화", null));
        CatalogMessages.ProductDetail detail = service.product("merchant-a",
                new CatalogMessages.ProductRequest("product-101"));

        // then 428 이전에는 두 응답 어디에도 이미지가 없어 Agent 가 그림을 못 받았다
        assertEquals("https://cdn.example.test/product-101.jpg", response.items().get(0).imageUrl());
        assertEquals("https://cdn.example.test/product-101.jpg", detail.imageUrl());
    }

    @Test
    @DisplayName("빈 검색어는 판매 중인 상품을 전부 돌려준다")
    void search_blankQuery_returnsAllActiveProducts() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("", null));

        // then
        assertEquals(List.of("product-101", "product-102", "product-103", "product-104"), idsOf(response));
    }

    @Test
    @DisplayName("제목 부분일치로 검색한다")
    void search_partialTitle_returnsMatchingProducts() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("러닝화", null));

        // then
        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    @DisplayName("십만원 이하 러닝화 검색이 시드 상품을 돌려준다")
    void search_priceAndCurrencyFilter_returnsSeededProduct() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search("merchant-a", new CatalogMessages.SearchRequest(
                "러닝화", new CatalogMessages.SearchRequest.Filters(100000L, "KRW")));

        // then
        assertEquals(1, response.items().size());
        CatalogMessages.Item item = response.items().get(0);
        assertEquals("product-101", item.productId());
        assertEquals(89000L, item.price().amount());
        assertEquals("KRW", item.price().currency());
        assertEquals("IN_STOCK", item.availability());
    }

    @Test
    @DisplayName("상한가를 넘는 상품은 빠진다")
    void search_maxPriceFilter_excludesExpensiveProducts() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search("merchant-a", new CatalogMessages.SearchRequest(
                "", new CatalogMessages.SearchRequest.Filters(100000L, null)));

        // then
        assertEquals(List.of("product-101", "product-104"), idsOf(response));
    }

    @Test
    @DisplayName("통화가 다르면 빠진다")
    void search_otherCurrency_returnsEmpty() {
        // given & when
        CatalogMessages.ItemsResponse response = service.search("merchant-a", new CatalogMessages.SearchRequest(
                "", new CatalogMessages.SearchRequest.Filters(null, "USD")));

        // then
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("결과가 없으면 빈 배열이다")
    void search_noMatch_returnsEmpty() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("없는상품", null));

        // then
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("단종 상품은 검색에 나오지 않는다")
    void search_discontinuedProduct_returnsEmpty() {
        // given & when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("백팩", null));

        // then
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("모든 옵션이 품절이면 OUT_OF_STOCK 이다")
    void search_allVariantsSoldOut_marksOutOfStock() {
        // given & when
        CatalogService soldOutService = serviceOf(List.of(CatalogFixture.soldOut()));

        CatalogMessages.ItemsResponse response =
                soldOutService.search("merchant-a", new CatalogMessages.SearchRequest("", null));

        // then
        assertEquals("OUT_OF_STOCK", response.items().get(0).availability());
    }

    @Test
    @DisplayName("일괄조회는 요청한 순서로 돌려준다")
    void lookup_multipleIds_keepsRequestedOrder() {
        // given & when
        CatalogMessages.ItemsResponse response = service.lookup("merchant-a",
                new CatalogMessages.LookupRequest(List.of("product-103", "product-101")));

        // then
        assertEquals(List.of("product-103", "product-101"), idsOf(response));
    }

    @Test
    @DisplayName("일괄조회에서 없는 ID 는 조용히 빠진다")
    void lookup_unknownId_dropsItSilently() {
        // given & when
        CatalogMessages.ItemsResponse response = service.lookup("merchant-a",
                new CatalogMessages.LookupRequest(List.of("product-101", "product-999", "product-105")));

        // then
        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    @DisplayName("일괄조회에 빈 목록을 주면 빈 배열이다")
    void lookup_emptyRequest_returnsEmpty() {
        // when & then
        assertTrue(service.lookup("merchant-a", new CatalogMessages.LookupRequest(List.of())).items().isEmpty());
        assertTrue(service.lookup("merchant-a", new CatalogMessages.LookupRequest(null)).items().isEmpty());
        assertTrue(service.lookup("merchant-a", null).items().isEmpty());
    }

    @Test
    @DisplayName("상세조회는 옵션을 묶어서 돌려준다")
    void product_existingId_groupsOptions() {
        // given & when
        CatalogMessages.ProductDetail detail =
                service.product("merchant-a", new CatalogMessages.ProductRequest("product-101"));

        // then
        assertEquals("product-101", detail.productId());
        assertEquals(89000L, detail.price().amount());
        assertEquals(1, detail.options().size());
        assertEquals("size", detail.options().get(0).name());
        assertEquals(List.of("260", "270"),
                detail.options().get(0).values().stream().map(CatalogOptionValue::label).toList());
    }

    @Test
    @DisplayName("품절 옵션은 상세조회에서 빠진다")
    void product_soldOutVariant_isHidden() {
        // given & when
        CatalogMessages.ProductDetail detail =
                service.product("merchant-a", new CatalogMessages.ProductRequest("product-101"));

        List<String> skus = detail.options().get(0).values().stream()
                .map(CatalogOptionValue::optionValueId).toList();

        // then
        assertFalse(skus.contains("product-101-280"), "stock 이 0 인 SKU 는 노출하지 않는다");
    }

    @Test
    @DisplayName("옵션이 여러 묶음이면 묶음별로 나눈다")
    void product_multipleOptionGroups_splitsByName() {
        // given & when
        CatalogMessages.ProductDetail detail =
                service.product("merchant-a", new CatalogMessages.ProductRequest("product-103"));

        // then
        assertEquals(1, detail.options().size());
        assertEquals("color", detail.options().get(0).name());
        assertEquals(2, detail.options().get(0).values().size());
    }

    @Test
    @DisplayName("없는 상품 상세조회는 PRODUCT_NOT_FOUND 다")
    void product_unknownId_throwsProductNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.product("merchant-a", new CatalogMessages.ProductRequest("product-999")));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
        assertEquals(404, thrown.status().value());
    }

    @Test
    @DisplayName("단종 상품 상세조회도 PRODUCT_NOT_FOUND 다")
    void product_discontinuedId_throwsProductNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.product("merchant-a", new CatalogMessages.ProductRequest("product-105")));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    @DisplayName("productId 가 비면 PRODUCT_NOT_FOUND 다")
    void product_blankId_throwsProductNotFound() {
        // when & then
        assertThrows(ApiException.class, () -> service.product("merchant-a", new CatalogMessages.ProductRequest("")));
        assertThrows(ApiException.class, () -> service.product("merchant-a", new CatalogMessages.ProductRequest(null)));
        assertThrows(ApiException.class, () -> service.product("merchant-a", null));
    }

    @Test
    @DisplayName("다른 몰에서는 그 상품이 보이지 않고, 모르는 몰은 404 MERCHANT_NOT_FOUND 다")
    void search_otherOrUnknownMall_isScoped() {
        // given: 시드는 전부 merchant-a 다
        CatalogMessages.SearchRequest all = new CatalogMessages.SearchRequest("", null);

        // when & then: merchant-b 는 등록된 몰이지만 상품이 없다
        assertTrue(service.search("merchant-b", all).items().isEmpty());
        assertTrue(service.lookup("merchant-b", new CatalogMessages.LookupRequest(List.of("product-101"))).items()
                .isEmpty());
        ApiException notInMall = assertThrows(ApiException.class,
                () -> service.product("merchant-b", new CatalogMessages.ProductRequest("product-101")));
        assertEquals("PRODUCT_NOT_FOUND", notInMall.errorCode());

        // 모르는 몰은 상품을 보기 전에 거절한다
        ApiException unknown = assertThrows(ApiException.class, () -> service.search("merchant-z", all));
        assertEquals("MERCHANT_NOT_FOUND", unknown.errorCode());
        assertEquals(404, unknown.status().value());
    }

    @Test
    @DisplayName("동의어 묶음으로 운동화를 치면 러닝화도 나온다")
    void search_synonymGroup_findsOtherWordsForTheSameThing() {
        // given: 시드에는 "러닝화 A" 가 있고 "운동화" 라는 낱말은 어디에도 없다
        // when
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("운동화", null));

        // then 470 이전에는 제목 부분문자열 하나뿐이라 0건이었다
        assertEquals(List.of("product-101"), idsOf(response));
    }

    @Test
    @DisplayName("제목에서 맞은 상품이 카테고리로만 맞은 상품보다 앞에 온다")
    void search_titleMatch_outranksCategoryOnlyMatch() {
        // given: product-201 은 제목에 "운동화" 가 없고 카테고리에만 있다
        List<Product> products = new ArrayList<>(CatalogFixture.seed());
        products.add(CatalogFixture.categoryOnlyMatch());
        CatalogService ranked = serviceOf(products);

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("운동화", null));

        // then 첫 화면이 pageSize 4 라 순서가 곧 결과다
        assertEquals(List.of("product-101", "product-201"), idsOf(response));
    }

    @Test
    @DisplayName("낱말 여러 개는 하나만 맞아도 후보고, 다 맞은 것이 앞이며, 브랜드에서 맞아도 된다")
    void search_multipleTokens_orCandidatesRankedByMatchedWeight() {
        // given & when: "나이키" 는 브랜드에만, "러닝화" 는 제목에만 있다
        CatalogMessages.ItemsResponse both =
                service.search("merchant-a", new CatalogMessages.SearchRequest("나이키 러닝화", null));
        CatalogMessages.ItemsResponse split =
                service.search("merchant-a", new CatalogMessages.SearchRequest("아디다스 러닝화", null));

        // then 470 은 AND 라 "아디다스 러닝화" 가 0건이었다. 2차(6.7절)는 둘 다 후보고 끝 낱말 러닝화가 맞은 것이 앞이다
        assertEquals("product-101", idsOf(both).get(0));
        assertEquals(List.of("product-101", "product-102"), idsOf(split));
    }

    @Test
    @DisplayName("수식어가 붙은 검색어도 살아남는다 — 가벼운 러닝화")
    void search_modifierPlusNoun_stillFindsTheNoun() {
        // given & when: "가벼운" 은 어디에도 없다. 470 의 AND 규칙이면 0건이다
        CatalogMessages.ItemsResponse response =
                service.search("merchant-a", new CatalogMessages.SearchRequest("가벼운 러닝화", null));

        // then
        assertEquals("product-101", idsOf(response).get(0));
    }

    @Test
    @DisplayName("형용사 수식어는 검색어에서 빠진다 — 맛있는 우유는 우유만 찾고 맛있는 사과는 후보도 아니다(3차)")
    void search_adjectiveModifier_isRemovedByRefiner() {
        // given: 2차는 "맛있는" 을 0.2 로 깎아 사과를 뒤로 보냈다. 3차는 nori 가 형용사 어절을 버려 사과가 후보에서 빠진다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-401", "못생겨도 맛있는 사과 1kg", "과일 > 사과"),
                CatalogFixture.simple("product-402", "서울우유 나 100% 우유 1L", "유제품 > 우유")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("맛있는 우유", null));

        // then
        assertEquals(List.of("product-402"), idsOf(response));
    }

    @Test
    @DisplayName("검색어가 수식어뿐이면 감쇠하지 않는다 — 그때는 그것이 검색어다")
    void search_onlyFluffWords_stillMatches() {
        // given
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-401", "못생겨도 맛있는 사과 1kg", "과일 > 사과")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("맛있는", null));

        // then
        assertEquals(List.of("product-401"), idsOf(response));
    }

    @Test
    @DisplayName("끝 낱말 가중 — 유기농 토마토는 유기농 사과보다 토마토가 앞이다")
    void search_lastWord_isBoosted() {
        // given: 두 낱말이 각각 한 상품씩 맞아 흔한 낱말 가중치가 같다. 한국어 명사구는 핵심 명사가 뒤다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-411", "유기농 사과 2kg", "과일 > 사과"),
                CatalogFixture.simple("product-412", "대추방울 토마토 1kg", "채소 > 토마토")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("유기농 토마토", null));

        // then
        assertEquals(List.of("product-412", "product-411"), idsOf(response));
    }

    @Test
    @DisplayName("흔한 낱말이라고 가볍게 보지 않는다 — 국산 돼지고기는 둘 다 맞은 상품이 앞이고 나머지는 시드 순서다")
    void search_commonWord_isNotDemotedByFrequency() {
        // given: "국산" 은 네 상품에 다 있고 "돼지고기" 는 하나에만 있다. 역문서빈도를 넣었다가 뺐다(6.7절) —
        //        10만 건 풀에서 핵심 명사(양말·이불)가 흔한 낱말이라 가중치가 0 에 가까워지는 역효과가 났다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-421", "국산 참기름 300ml", "양념 > 참기름"),
                CatalogFixture.simple("product-422", "국산 스텐 반찬통", "주방 > 밀폐용기"),
                CatalogFixture.simple("product-423", "국산 햇 마늘 1kg", "채소 > 마늘"),
                CatalogFixture.simple("product-424", "미국산 돼지고기 목살 구이용", "돼지고기 > 목살")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("국산 돼지고기", null));

        // then: OR 라 네 건 전부 후보다
        assertEquals(List.of("product-424", "product-421", "product-422", "product-423"), idsOf(response));
    }

    @Test
    @DisplayName("낱말로 맞은 상품이 부분문자열로만 맞은 상품보다 앞에 온다")
    void search_wordBoundaryMatch_outranksSubstringMatch() {
        // given: 우유팩 키링이 시드에서 먼저다. 부분문자열만 보면 실제 우유와 구분되지 않는다.
        //        우유 상품을 넷 이상 두는 이유 — 후보가 한 화면(4건) 미만이면 3차 퍼지 백오프가 걸려 "우유팩" 이 동의어로 붙는다
        List<Product> products = new ArrayList<>(CatalogFixture.milkAndLookalikes());
        for (int i = 0; i < 3; i++) {
            products.add(CatalogFixture.simple("product-31" + i, "매일 우유 1L 묶음 " + i, "유제품 > 우유"));
        }
        CatalogService ranked = serviceOf(products);

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("우유", null));

        // then: 낱말로 맞은 넷이 앞(풀 순서), 부분문자열로만 맞은 키링이 맨 뒤
        List<String> ids = idsOf(response);
        assertEquals("product-302", ids.get(0));
        assertEquals("product-301", ids.get(ids.size() - 1));
    }

    @Test
    @DisplayName("결과가 많아도 몰 하나가 100건을 넘겨 주지 않는다")
    void search_manyMatches_isCappedPerMall() {
        // given: 상한이 없으면 빈 검색어 한 번에 카탈로그 전량이 나간다
        CatalogService bulk = serviceOf(CatalogFixture.many(120));

        // when
        CatalogMessages.ItemsResponse byKeyword =
                bulk.search("merchant-a", new CatalogMessages.SearchRequest("대량", null));
        CatalogMessages.ItemsResponse byBlank =
                bulk.search("merchant-a", new CatalogMessages.SearchRequest("", null));

        // then
        assertEquals(100, byKeyword.items().size());
        assertEquals(100, byBlank.items().size());
    }

    // ---------------------------------------------------------------- 3차(S19, ADR 0013)

    @Test
    @DisplayName("조사와 서술어가 붙은 문장형도 찾는다 — 우유를 찾아줘, 운동화가 필요해")
    void search_sentenceQuery_isRefinedToNoun() {
        // given: 2차는 "우유를" 이 어느 제목에도 없어 0건이었다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-501", "서울우유 나 100% 우유 1L", "유제품 > 우유")));

        // when & then
        assertEquals(List.of("product-501"),
                idsOf(ranked.search("merchant-a", new CatalogMessages.SearchRequest("우유를 찾아줘", null))));
        assertEquals(List.of("product-101"),
                idsOf(service.search("merchant-a", new CatalogMessages.SearchRequest("운동화가 필요해", null))));
    }

    @Test
    @DisplayName("뒤에 붙은 수식어 — 텀블러 큰 거 는 텀블러를 찾고 '거' 가 거치대를 끌어오지 않는다")
    void search_trailingModifier_doesNotBecomeHead() {
        // given: 2차는 마지막 낱말 "거" 에 1.5 를 줘서 거치대가 텀블러 위로 왔다(실측 0/4)
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-511", "휴대폰 거치대", "디지털 > 거치대"),
                CatalogFixture.simple("product-512", "스탠리 텀블러 591ml", "주방 > 텀블러")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("텀블러 큰 거", null));

        // then
        assertEquals(List.of("product-512"), idsOf(response));
    }

    @Test
    @DisplayName("속성 낱말이 뒤에 와도 머리 명사는 상품군이다 — 우유 대용량 은 대용량 세제보다 우유가 앞")
    void search_trailingAttribute_isDemoted() {
        // given: 실제 호출자(Haiku)가 "우유 대용량" 을 그대로 넘긴다. 2차는 대용량이 끝 낱말이라 1.5 를 받아 세제가 위로 왔다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-521", "대용량 액체 세제 3L", "생활 > 세제"),
                CatalogFixture.simple("product-522", "서울우유 나 100% 우유 1L", "유제품 > 우유")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("우유 대용량", null));

        // then: 우유(머리, 1.5) > 대용량만 맞은 세제(0.2)
        assertEquals(List.of("product-522", "product-521"), idsOf(response));
    }

    @Test
    @DisplayName("머리 명사 우세 — 수식어가 셋 맞은 상품보다 머리 명사 하나 맞은 상품이 앞이다")
    void search_headNoun_outranksAllModifiersCombined() {
        // given: 2차의 끝 낱말 1.5 는 수식어 둘(2.0)에 진다. 3차는 머리 가중치 = 나머지 합 + 0.5
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-531", "아기 신생아 목욕 장난감", "유아 > 목욕놀이"),
                CatalogFixture.simple("product-532", "극세사 타월 5장", "생활 > 타월")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("아기 신생아 목욕 타월", null));

        // then: 타월(3.5) > 아기+신생아+목욕(3.0)
        assertEquals(List.of("product-532", "product-531"), idsOf(response));
    }

    @Test
    @DisplayName("숫자가 든 토큰은 규격이다 — 텀블러 500ml 은 500ml 생수보다 텀블러가 앞")
    void search_digitToken_isTreatedAsAttribute() {
        // given
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-541", "삼다수 500ml 20병", "음료 > 생수"),
                CatalogFixture.simple("product-542", "스탠리 텀블러 스테인리스", "주방 > 텀블러")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("텀블러 500ml", null));

        // then
        assertEquals(List.of("product-542", "product-541"), idsOf(response));
    }

    @Test
    @DisplayName("검색어가 전부 속성·수식어면 깎지 않는다 — 대용량 세트 는 그대로 검색어다")
    void search_onlyAttributes_areNotDemoted() {
        // given
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-551", "대용량 물티슈 세트", "생활 > 물티슈"),
                CatalogFixture.simple("product-552", "서울우유 나 100% 우유 1L", "유제품 > 우유")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("대용량 세트", null));

        // then
        assertEquals(List.of("product-551"), idsOf(response));
    }

    @Test
    @DisplayName("띄어쓰기 무시 — 무선이어폰 이 '무선 이어폰' 제목에 맞는다")
    void search_noSpaceQuery_matchesSpacedTitle() {
        // given: 2차는 부분문자열 "무선이어폰" 이 어느 제목에도 없어 0건이었다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-561", "소니 무선 이어폰 WF-1000", "디지털 > 이어폰")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("무선이어폰", null));

        // then
        assertEquals(List.of("product-561"), idsOf(response));
    }

    @Test
    @DisplayName("조각 백오프 — 남자양말 이 어디에도 없으면 남자·양말 조각으로 찾는다")
    void search_pieceBackoff_whenWholeWordMatchesNothing() {
        // given: "남자양말" 은 붙여서도 띄어서도 제목에 없다. 조각 둘이 각각 제목에 있다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-571", "남자 스포츠 양말 5족", "패션 > 양말")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("남자양말", null));

        // then
        assertEquals(List.of("product-571"), idsOf(response));
    }

    @Test
    @DisplayName("퍼지 백오프 — 쵸콜릿 은 자모가 비슷한 제목 낱말 초콜릿 으로 찾는다")
    void search_fuzzyBackoff_findsSpellingVariant() {
        // given
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-581", "다크 초콜릿 100g", "과자 > 초콜릿")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("쵸콜릿", null));

        // then
        assertEquals(List.of("product-581"), idsOf(response));
    }

    @Test
    @DisplayName("안전망 — 전부 걸러지는 검색어는 공백 분리 원문으로 돌아간다. 서술어 하나만 치면 지금처럼 0건")
    void search_whenRefinerDropsEverything_fallsBackToRawTokens() {
        // given
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-591", "봄 달래 나물 200g", "채소 > 나물")));

        // when: "달래" 를 nori 가 동사로 읽어도 원문으로 돌아가 제목에 맞는다
        CatalogMessages.ItemsResponse found =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("달래", null));
        CatalogMessages.ItemsResponse none =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("찾아줘", null));

        // then
        assertEquals(List.of("product-591"), idsOf(found));
        assertTrue(none.items().isEmpty());
    }

    @Test
    @DisplayName("영어 동의어 — milk 로 우유를 찾는다")
    void search_englishSynonym_mapsToKorean() {
        // given: 픽스처 동의어에 ["milk", "우유"] 가 있다(application.yml 과 같은 모양)
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-601", "서울우유 나 100% 우유 1L", "유제품 > 우유")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("milk", null));

        // then
        assertEquals(List.of("product-601"), idsOf(response));
    }

    @Test
    @DisplayName("4차 — 점수가 같으면 카테고리가 머리 명사로 끝나는 상품이 먼저다")
    void search_tie_prefersCategoryEndingWithHead() {
        // given: 풀 순서로는 딸기 스푼이 먼저다(실서비스에서 다이소가 컬리보다 앞선다)
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-701", "퓨어 딸기 스푼 3개입", "주방용품 > 수저/커트러리 > 숟가락"),
                CatalogFixture.simple("product-702", "국산 냉동 딸기 1kg", "과일/견과 > 딸기")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("딸기", null));

        // then
        assertEquals(List.of("product-702", "product-701"), idsOf(response));
    }

    @Test
    @DisplayName("4차 — 카테고리에 머리 명사가 앞에 붙기만 한 부속품은 일치가 아니다")
    void search_tie_categoryPrefixIsNotMatch() {
        // given: "칫솔걸이/꽂이" 는 칫솔로 시작하지만 걸이다. 부분 일치로 보면 칫솔 캡이 칫솔보다 먼저 온다(장부 8절 라운드 1)
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-711", "튤립 모양 칫솔 캡 2P", "청소/욕실 > 욕실용품 > 칫솔걸이/꽂이"),
                CatalogFixture.simple("product-712", "토끼모양 어린이 칫솔 3개입", "뷰티/위생 > 구강용품 > 칫솔")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("칫솔", null));

        // then
        assertEquals(List.of("product-712", "product-711"), idsOf(response));
    }

    @Test
    @DisplayName("4차 — 반려동물 검색이 아니면 같은 점수 안에서 펫 상품이 뒤로 간다")
    void search_tie_demotesPetProductsForNonPetQuery() {
        // given: 두 상품 모두 카테고리가 치즈로 끝나지 않아 카테고리 규칙이 가르지 못한다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-721", "[펫] 강아지용 치즈 버거 120g", "반려동물 > 간식"),
                CatalogFixture.simple("product-722", "더블 치즈 버거 2입", "식품 > 간편식")));

        // when
        CatalogMessages.ItemsResponse plain =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("치즈", null));
        CatalogMessages.ItemsResponse forPet =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("고양이 치즈", null));

        // then 반려동물 검색이면 뒤로 보내지 않는다 — 풀 순서 그대로
        assertEquals(List.of("product-722", "product-721"), idsOf(plain));
        assertEquals(List.of("product-721", "product-722"), idsOf(forPet));
    }

    @Test
    @DisplayName("4차 — 계란으로 달걀을 찾는다")
    void search_eggSynonym_findsDalgyal() {
        // given: 컬리는 달걀로 쓴다. 풀 1,146건 중 제목에 계란이 든 것은 8건이었다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-731", "손잡이가 접히는 계란 틀", "주방용품 > 조리도구 > 조리소도구"),
                CatalogFixture.simple("product-732", "무항생제 달걀 L(대란) 20구", "정육·가공육·달걀 > 달걀·가공란")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("계란", null));

        // then 달걀이 동의어로 걸리고, 카테고리가 달걀로 끝나 계란 틀보다 먼저 온다
        assertEquals(List.of("product-732", "product-731"), idsOf(response));
    }

    @Test
    @DisplayName("5차 — 같은 점수면 부속품(운동화 끈)이 진짜 상품(러닝화) 뒤로 간다")
    void search_tie_demotesAccessories() {
        // given: 다이소가 운동화 끈을 "신발" 칸에 넣어 카테고리 일치로도 가르지 못한다. 풀 순서로는 끈이 먼저다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-741", "대각 운동화 끈 10 mm 2개입", "패션/잡화 > 잡화 > 신발"),
                CatalogFixture.simple("product-742", "나이키 윈플로 12 남성 러닝화", "스포츠/레저 > 스포츠화")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("운동화", null));

        // then
        assertEquals(List.of("product-742", "product-741"), idsOf(response));
    }

    @Test
    @DisplayName("5차 — 섞인 칸(스탠드/무드등)으로만 맞은 머리 명사는 점수를 받지 못한다")
    void search_mixedCategoryOnlyHead_losesHeadWeight() {
        // given: 무드등은 "스탠드/무드등" 칸 이름으로만 스탠드에 맞는다. 4차는 이 칸을 카테고리 일치로 쳐 무드등을 올렸다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-751", "촛불 모양 LED 무드등 3개입", "공구/디지털 > 조명/전구 > 스탠드/무드등"),
                CatalogFixture.simple("product-752", "루메나 LED 스탠드 DESK ON", "가전제품 > 생활가전")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("스탠드", null));

        // then
        assertEquals(List.of("product-752", "product-751"), idsOf(response));
    }

    @Test
    @DisplayName("5차 — 신체 부위 낱말(손톱)은 끝에 와도 머리 명사가 아니다")
    void search_headBlockedWord_isNotHead() {
        // given: "핸드크림 손톱" 에서 손톱이 머리가 되면 손톱깎이가 핸드크림보다 위다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-761", "스테인리스 손톱깎이", "뷰티/위생 > 뷰티소품 > 네일/패디소품"),
                CatalogFixture.simple("product-762", "퍼퓸 핸드크림 30mL", "헤어·바디·구강 > 핸드·립·데오")));

        // when
        CatalogMessages.ItemsResponse response =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("핸드크림 손톱", null));

        // then
        assertEquals(List.of("product-762", "product-761"), idsOf(response));
    }

    @Test
    @DisplayName("5차 — 가방 칸은 부속품 칸이지만 머리 명사가 가방 종류면 예외다")
    void search_bagCategory_isAccessoryUnlessBagHead() {
        // given: 다이소 백팩과 패딩 크로스백의 말단 칸이 둘 다 "가방" 이다
        CatalogService ranked = serviceOf(List.of(
                CatalogFixture.simple("product-771", "나일론 경량 앞포켓 백팩", "패션/잡화 > 잡화 > 가방"),
                CatalogFixture.simple("product-772", "행운 백팩 꾸미기 카드 5세트", "문구/팬시 > 카드/편지/봉투 > 카드/엽서/연하장"),
                CatalogFixture.simple("product-773", "볼륨 패딩 미니 크로스백", "패션/잡화 > 잡화 > 가방"),
                CatalogFixture.simple("product-774", "덕다운 크롭 패딩 블랙", "패션의류 > 아우터")));

        // when
        CatalogMessages.ItemsResponse backpack =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("백팩", null));
        CatalogMessages.ItemsResponse padding =
                ranked.search("merchant-a", new CatalogMessages.SearchRequest("패딩", null));

        // then 백팩 검색에서는 백팩이 꾸미기 카드 뒤로 밀리지 않고, 패딩 검색에서는 크로스백이 패딩 뒤로 간다
        assertEquals(List.of("product-771", "product-772"), idsOf(backpack));
        assertEquals(List.of("product-774", "product-773"), idsOf(padding));
    }
}
