package com.copenhagen.core.product;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;

import com.copenhagen.contract.ucp.CatalogOptionValue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private final CatalogSearchFanout fanout = new CatalogSearchFanout(3, 12, java.time.Duration.ofSeconds(2));

    @org.junit.jupiter.api.AfterEach
    void closeFanout() {
        fanout.close();
    }

    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private MerchantCatalogClient merchantA;

    @Mock
    private MerchantCatalogClient merchantB;

    @BeforeEach
    void setUp() {
        lenient().when(merchantA.merchantId()).thenReturn("merchant-a");
        lenient().when(merchantA.merchantName()).thenReturn("A몰");
        lenient().when(merchantA.baseUrl()).thenReturn("http://merchant-a:8080");
        lenient().when(merchantB.merchantId()).thenReturn("merchant-b");
        lenient().when(merchantB.merchantName()).thenReturn("B몰");
    }

    private ProductService service(MerchantCatalogClient... clients) {
        Map<String, MerchantCatalogClient> map = new LinkedHashMap<>();
        for (MerchantCatalogClient client : clients) {
            map.put(client.merchantId(), client);
        }
        return new ProductService(map, CLOCK, fanout);
    }

    private static SearchQuery query(String query) {
        return new SearchQuery(query, "", "", "", null, null, 1, 4, "RELEVANCE");
    }

    private static MerchantCatalogClient.CatalogItem item(String id, String title, long price) {
        return new MerchantCatalogClient.CatalogItem(
                id, title, new MerchantCatalogClient.CatalogPrice(price, "KRW"), "IN_STOCK");
    }

    @Test
    @DisplayName("Merchant 이미지를 내부 검색·상세·장바구니 스냅샷까지 보존한다")
    void image_survivesInternalSearchDetailAndCartCapture() {
        String image = "https://cdn.daisomall.co.kr/test.jpg";
        when(merchantA.search("셔츠", null, "KRW")).thenReturn(List.of(
                new MerchantCatalogClient.CatalogItem("p-1", "셔츠",
                        new MerchantCatalogClient.CatalogPrice(9900, "KRW"), "IN_STOCK", image)));
        when(merchantA.product("p-1")).thenReturn(new MerchantCatalogClient.CatalogProductDetail(
                "p-1", "셔츠", "설명", new MerchantCatalogClient.CatalogPrice(9900, "KRW"),
                List.of(new MerchantCatalogClient.CatalogOption("색상", List.of(
                        new CatalogOptionValue("sku-1", "흰색")))), "IN_STOCK", image));
        ProductService service = service(merchantA);

        assertThat(service.searchForInternal("셔츠", "merchant-a", null, null).items().get(0).imageUrl())
                .isEqualTo(image);
        assertThat(service.detailForInternal("merchant-a", "p-1").imageUrl()).isEqualTo(image);
        assertThat(service.captureForCart("merchant-a", "p-1", List.of("sku-1")).imageUrl()).isEqualTo(image);
    }

    @Test
    @DisplayName("단일 merchant 검색 성공 시 status=OK, offers 1개")
    void search_singleMerchantSuccess_ok() {
        when(merchantA.search("셔츠", null, "KRW")).thenReturn(List.of(item("p-1", "린넨 셔츠", 9900)));
        ProductService service = service(merchantA);

        SearchEnvelope result = service.search(PRINCIPAL, query("셔츠"));

        assertThat(result.publicUserRef()).isEqualTo(PRINCIPAL.toString());
        assertThat(result.connectionRef()).isEqualTo("default");
        assertThat(result.data().status()).isEqualTo("OK");
        assertThat(result.data().products()).hasSize(1);
        assertThat(result.data().products().get(0).offers()).containsExactly(
                new Offer("merchant-a", "A몰", 9900L, "KRW", "IN_STOCK"));
        assertThat(result.data().failedMerchants()).isEmpty();
        assertThat(result.data().total()).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 merchant 결과를 각각 별도 상품으로 합친다(같은 상품 매칭은 하지 않음)")
    void search_multipleMerchants_combinesAsSeparateProducts() {
        when(merchantA.search("셔츠", null, "KRW")).thenReturn(List.of(item("p-1", "셔츠A", 9900)));
        when(merchantB.search("셔츠", null, "KRW")).thenReturn(List.of(item("p-2", "셔츠B", 12000)));
        ProductService service = service(merchantA, merchantB);

        SearchEnvelope result = service.search(PRINCIPAL, query("셔츠"));

        assertThat(result.data().status()).isEqualTo("OK");
        assertThat(result.data().products()).hasSize(2);
        assertThat(result.data().merchants()).containsExactlyInAnyOrder(
                new MerchantRef("merchant-a", "A몰"), new MerchantRef("merchant-b", "B몰"));
    }

    @Test
    @DisplayName("일부 merchant 호출 실패 시 status=PARTIAL, 나머지 결과는 유지")
    void search_partialFailure_returnsPartial() {
        when(merchantA.search("셔츠", null, "KRW")).thenReturn(List.of(item("p-1", "셔츠A", 9900)));
        when(merchantB.search("셔츠", null, "KRW"))
                .thenThrow(new CoreException(CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE));
        ProductService service = service(merchantA, merchantB);

        SearchEnvelope result = service.search(PRINCIPAL, query("셔츠"));

        assertThat(result.data().status()).isEqualTo("PARTIAL");
        assertThat(result.data().failedMerchants()).containsExactly("merchant-b");
        assertThat(result.data().products()).hasSize(1);
    }

    @Test
    @DisplayName("모든 merchant 호출이 실패해도 PARTIAL이다(EMPTY가 아님)")
    void search_allFail_stillPartial() {
        when(merchantA.search("셔츠", null, "KRW"))
                .thenThrow(new CoreException(CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE));
        ProductService service = service(merchantA);

        SearchEnvelope result = service.search(PRINCIPAL, query("셔츠"));

        assertThat(result.data().status()).isEqualTo("PARTIAL");
        assertThat(result.data().products()).isEmpty();
    }

    @Test
    @DisplayName("결과가 없으면 EMPTY다")
    void search_noResults_empty() {
        when(merchantA.search("없는상품", null, "KRW")).thenReturn(List.of());
        ProductService service = service(merchantA);

        assertThat(service.search(PRINCIPAL, query("없는상품")).data().status()).isEqualTo("EMPTY");
    }

    @Test
    @DisplayName("category나 brand를 지정하면 항상 빈 결과다(실제 category 데이터 없음)")
    void search_categoryFilter_alwaysEmpty() {
        when(merchantA.search("셔츠", null, "KRW")).thenReturn(List.of(item("p-1", "셔츠", 9900)));
        ProductService service = service(merchantA);
        SearchQuery withCategory = new SearchQuery("셔츠", "상의", "", "", null, null, 1, 4, "RELEVANCE");

        assertThat(service.search(PRINCIPAL, withCategory).data().products()).isEmpty();
    }

    @Test
    @DisplayName("KRW가 아닌 offer는 제외한다")
    void search_nonKrwOffer_excluded() {
        MerchantCatalogClient.CatalogItem usdItem = new MerchantCatalogClient.CatalogItem(
                "p-1", "셔츠", new MerchantCatalogClient.CatalogPrice(10, "USD"), "IN_STOCK");
        when(merchantA.search("셔츠", null, "KRW")).thenReturn(List.of(usdItem));
        ProductService service = service(merchantA);

        assertThat(service.search(PRINCIPAL, query("셔츠")).data().products()).isEmpty();
    }

    @Test
    @DisplayName("PRICE_ASC 정렬은 가격 오름차순이다")
    void search_sortPriceAsc() {
        when(merchantA.search("", null, "KRW")).thenReturn(List.of(
                item("p-1", "비싼거", 20000), item("p-2", "싼거", 5000)));
        ProductService service = service(merchantA);
        SearchQuery sorted = new SearchQuery("", "", "", "", null, null, 1, 4, "PRICE_ASC");

        List<CatalogProduct> products = service.search(PRINCIPAL, sorted).data().products();

        assertThat(products).extracting(p -> p.offers().get(0).price()).containsExactly(5000L, 20000L);
    }

    @Test
    @DisplayName("page*pageSize < total이면 hasNext=true다")
    void search_pagination_hasNext() {
        when(merchantA.search("", null, "KRW")).thenReturn(List.of(
                item("p-1", "A", 1000), item("p-2", "B", 2000), item("p-3", "C", 3000)));
        ProductService service = service(merchantA);
        SearchQuery paged = new SearchQuery("", "", "", "", null, null, 1, 2, "RELEVANCE");

        SearchEnvelope result = service.search(PRINCIPAL, paged);

        assertThat(result.data().products()).hasSize(2);
        assertThat(result.data().total()).isEqualTo(3);
        assertThat(result.data().hasNext()).isTrue();
    }

    @Test
    @DisplayName("옵션이 없는 상품 + optionId 미지정이면 NONE이고 기본가를 그대로 보여준다")
    void detail_noVariants_noOptionId_none() {
        MerchantCatalogClient.CatalogProductDetail detail = new MerchantCatalogClient.CatalogProductDetail(
                "p-1", "무옵션상품", "설명", new MerchantCatalogClient.CatalogPrice(9900, "KRW"),
                List.of(), "IN_STOCK");
        when(merchantA.product("p-1")).thenReturn(detail);
        ProductService service = service(merchantA);

        DetailEnvelope result = service.getDetail(PRINCIPAL, "p-1", "merchant-a", "");

        assertThat(result.data().status()).isEqualTo("FOUND");
        assertThat(result.data().product().selectionStatus()).isEqualTo("NONE");
        assertThat(result.data().product().offer().price()).isEqualTo(9900L);
        assertThat(result.data().product().optionsRequired()).isFalse();
    }

    @Test
    @DisplayName("옵션이 있는 상품 + optionId 미지정이면 REQUIRED이고 가격을 숨긴다")
    void detail_hasVariants_noOptionId_required() {
        when(merchantA.product("p-1")).thenReturn(optionedDetail());
        ProductService service = service(merchantA);

        DetailEnvelope result = service.getDetail(PRINCIPAL, "p-1", "merchant-a", "");

        assertThat(result.data().product().selectionStatus()).isEqualTo("REQUIRED");
        assertThat(result.data().product().offer().price()).isNull();
        assertThat(result.data().product().optionsRequired()).isTrue();
    }

    @Test
    @DisplayName("존재하는 optionId를 선택하면 VALID이고 선택된 variant 가격이 offer와 같다")
    void detail_validOption_matchesOfferPrice() {
        when(merchantA.product("p-1")).thenReturn(optionedDetail());
        ProductService service = service(merchantA);

        DetailEnvelope result = service.getDetail(PRINCIPAL, "p-1", "merchant-a", "sku-1");

        ProductDetail product = result.data().product();
        assertThat(product.selectionStatus()).isEqualTo("VALID");
        assertThat(product.selectedOptionId()).isEqualTo("sku-1");
        assertThat(product.offer().price()).isEqualTo(9900L);
        Variant selected = product.variants().stream()
                .filter(variant -> variant.optionId().equals("sku-1")).findFirst().orElseThrow();
        assertThat(selected.price()).isEqualTo(product.offer().price());
        assertThat(selected.availability()).isEqualTo(product.offer().availability());
    }

    @Test
    @DisplayName("존재하지 않는 optionId를 주면 INVALID이고 가격을 숨긴다")
    void detail_unknownOption_invalid() {
        when(merchantA.product("p-1")).thenReturn(optionedDetail());
        ProductService service = service(merchantA);

        DetailEnvelope result = service.getDetail(PRINCIPAL, "p-1", "merchant-a", "sku-없음");

        assertThat(result.data().product().selectionStatus()).isEqualTo("INVALID");
        assertThat(result.data().product().offer().price()).isNull();
        assertThat(result.data().product().selectedOptionId()).isNull();
    }

    @Test
    @DisplayName("없는 상품은 200 응답에 status=NOT_FOUND, product=null로 표현한다")
    void detail_notFound_returns200WithNotFoundStatus() {
        when(merchantA.product("unknown")).thenThrow(new CoreException(CoreErrorCode.PRODUCT_NOT_FOUND));
        ProductService service = service(merchantA);

        DetailEnvelope result = service.getDetail(PRINCIPAL, "unknown", "merchant-a", "");

        assertThat(result.data().status()).isEqualTo("NOT_FOUND");
        assertThat(result.data().product()).isNull();
    }

    @Test
    @DisplayName("등록되지 않은 merchantId는 INVALID_REQUEST 예외다")
    void detail_unknownMerchant_throwsInvalidRequest() {
        ProductService service = service(merchantA);

        assertThatThrownBy(() -> service.getDetail(PRINCIPAL, "p-1", "merchant-z", ""))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("담기 캡처: 옵션을 지정하면 첫 값이 merchantVariantId가 되고 상품 정보를 그대로 캡처한다")
    void capture_withOption_usesFirstOptionValueId() {
        when(merchantA.product("p-1")).thenReturn(optionedDetail());
        ProductService service = service(merchantA);

        CartProductSnapshot snapshot = service.captureForCart("merchant-a", "p-1", List.of("sku-1"));

        assertThat(snapshot.merchantProductId()).isEqualTo("p-1");
        assertThat(snapshot.merchantVariantId()).isEqualTo("sku-1");
        assertThat(snapshot.productName()).isEqualTo("옵션상품");
        assertThat(snapshot.unitPriceAmount()).isEqualTo(9900L);
        assertThat(snapshot.currency()).isEqualTo("KRW");
        assertThat(snapshot.selectedOptions()).containsExactly(
                new CartProductSnapshot.SelectedOption("sku-1", "색상", "화이트"));
    }

    @Test
    @DisplayName("담기 캡처: Catalog에 url·이미지가 없어 productUrl은 합성하고 imageUrl은 null이다")
    void capture_synthesizesProductUrl() {
        when(merchantA.product("p-1")).thenReturn(optionedDetail());
        ProductService service = service(merchantA);

        CartProductSnapshot snapshot = service.captureForCart("merchant-a", "p-1", List.of("sku-1"));

        assertThat(snapshot.productUrl()).isEqualTo("http://merchant-a:8080/products/p-1");
        assertThat(snapshot.imageUrl()).isNull();
    }

    @Test
    @DisplayName("담기 캡처: 옵션 미지정 + 선택지가 하나면 자동 선택한다")
    void capture_noOption_singleChoice_autoSelects() {
        when(merchantA.product("p-1")).thenReturn(optionedDetail());
        ProductService service = service(merchantA);

        CartProductSnapshot snapshot = service.captureForCart("merchant-a", "p-1", List.of());

        assertThat(snapshot.merchantVariantId()).isEqualTo("sku-1");
    }

    @Test
    @DisplayName("담기 캡처: 옵션 미지정 + 선택지가 둘 이상이면 CART_OPTION_REQUIRED다")
    void capture_noOption_multipleChoices_requiresSelection() {
        MerchantCatalogClient.CatalogProductDetail detail = new MerchantCatalogClient.CatalogProductDetail(
                "p-1", "옵션상품", "설명", new MerchantCatalogClient.CatalogPrice(9900, "KRW"),
                List.of(new MerchantCatalogClient.CatalogOption("색상", List.of(
                        new CatalogOptionValue("sku-1", "화이트"),
                        new CatalogOptionValue("sku-2", "블랙")))),
                "IN_STOCK");
        when(merchantA.product("p-1")).thenReturn(detail);
        ProductService service = service(merchantA);

        assertThatThrownBy(() -> service.captureForCart("merchant-a", "p-1", List.of()))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.CART_OPTION_REQUIRED);
    }

    @Test
    @DisplayName("담기 캡처: 구매 가능한 옵션이 하나도 없으면 INVALID_REQUEST다")
    void capture_noPurchasableOption_throwsInvalidRequest() {
        MerchantCatalogClient.CatalogProductDetail detail = new MerchantCatalogClient.CatalogProductDetail(
                "p-1", "품절상품", "설명", new MerchantCatalogClient.CatalogPrice(9900, "KRW"),
                List.of(), "OUT_OF_STOCK");
        when(merchantA.product("p-1")).thenReturn(detail);
        ProductService service = service(merchantA);

        assertThatThrownBy(() -> service.captureForCart("merchant-a", "p-1", List.of()))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("담기 캡처: 상세에 없는 optionValueId는 INVALID_REQUEST다")
    void capture_unknownOptionValueId_throwsInvalidRequest() {
        when(merchantA.product("p-1")).thenReturn(optionedDetail());
        ProductService service = service(merchantA);

        assertThatThrownBy(() -> service.captureForCart("merchant-a", "p-1", List.of("sku-없음")))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("담기 캡처: KRW가 아닌 상품은 INVALID_REQUEST다")
    void capture_nonKrw_throwsInvalidRequest() {
        MerchantCatalogClient.CatalogProductDetail detail = new MerchantCatalogClient.CatalogProductDetail(
                "p-1", "USD상품", "설명", new MerchantCatalogClient.CatalogPrice(10, "USD"),
                List.of(new MerchantCatalogClient.CatalogOption(
                        "색상", List.of(new CatalogOptionValue("sku-1", "화이트")))),
                "IN_STOCK");
        when(merchantA.product("p-1")).thenReturn(detail);
        ProductService service = service(merchantA);

        assertThatThrownBy(() -> service.captureForCart("merchant-a", "p-1", List.of("sku-1")))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("담기 캡처: 등록되지 않은 merchantId는 INVALID_REQUEST다")
    void capture_unknownMerchant_throwsInvalidRequest() {
        ProductService service = service(merchantA);

        assertThatThrownBy(() -> service.captureForCart("merchant-z", "p-1", List.of()))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("담기 캡처: 없는 상품은 PRODUCT_NOT_FOUND를 그대로 전파한다(상세 조회와 달리 200이 아니다)")
    void capture_unknownProduct_propagatesNotFound() {
        when(merchantA.product("unknown")).thenThrow(new CoreException(CoreErrorCode.PRODUCT_NOT_FOUND));
        ProductService service = service(merchantA);

        assertThatThrownBy(() -> service.captureForCart("merchant-a", "unknown", List.of()))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    void optionPrice_cartAndDetailUseMerchantVariantPriceInsteadOfBasePrice() {
        String productId = "kurly:1000025270";
        String sku = "kurly:1000025270-M00000008360";
        when(merchantA.product(productId)).thenReturn(new MerchantCatalogClient.CatalogProductDetail(
                productId, "옵션 상품", "설명", new MerchantCatalogClient.CatalogPrice(10500L, "KRW"),
                List.of(new MerchantCatalogClient.CatalogOption("종류",
                        List.of(new CatalogOptionValue(sku, "추가 구성", 19600L, 100)))), "IN_STOCK"));
        ProductService service = service(merchantA);
        assertThat(service.captureForCart("merchant-a", productId, List.of(sku)).unitPriceAmount()).isEqualTo(19600L);
        CatalogProductView detail = service.detailForInternal("merchant-a", productId);
        assertThat(detail.optionGroups().get(0).values().get(0).priceAmount()).isEqualTo(19600L);
        assertThat(detail.optionGroups().get(0).values().get(0).stockQuantity()).isEqualTo(100);
        assertThat(service.getDetail(UUID.randomUUID(), productId, "merchant-a", sku)
                .data().product().offer().price()).isEqualTo(19600L);
    }

    private static MerchantCatalogClient.CatalogProductDetail optionedDetail() {
        return new MerchantCatalogClient.CatalogProductDetail(
                "p-1", "옵션상품", "설명", new MerchantCatalogClient.CatalogPrice(9900, "KRW"),
                List.of(new MerchantCatalogClient.CatalogOption(
                        "색상", List.of(new CatalogOptionValue("sku-1", "화이트")))),
                "IN_STOCK");
    }
}
