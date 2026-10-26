package com.copenhagen.core.internalapi.catalog;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.common.auth.CoreAuthenticationFilter;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.internalapi.McpContractAssertions;
import com.copenhagen.core.internalapi.config.InternalApiProperties;
import com.copenhagen.core.internalapi.error.InternalApiExceptionHandler;
import com.copenhagen.core.product.CatalogProductView;
import com.copenhagen.core.product.CatalogSearchItem;
import com.copenhagen.core.product.CatalogSearchResult;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.product.ProductService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설계서 §4.1·§4.2 — 내부 Catalog 응답이 mcp-server 계약을 통과하는지 JSON 수준에서 확인한다.
 *
 * <p>인증 필터는 슬라이스에서 뺀다({@code CartControllerTest}와 같은 이유). 검증 대상은 응답 형태다.
 */
@WebMvcTest(
        controllers = InternalCatalogController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = CoreAuthenticationFilter.class))
@Import({InternalCatalogService.class, InternalApiExceptionHandler.class,
        InternalCatalogControllerTest.TestBeans.class})
class InternalCatalogControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-18T05:00:00Z");
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @TestConfiguration
    static class TestBeans {

        @Bean
        Clock coreClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        InternalApiProperties internalApiProperties() {
            return new InternalApiProperties(Duration.ofMinutes(30), 10);
        }

        @Bean
        MerchantsProperties merchantsProperties() {
            return new MerchantsProperties(List.of(
                    merchant("merchant-a", 1L, "Copenhagen Demo Store"),
                    merchant("merchant-b", 2L, "노을상점")));
        }

        private static MerchantsProperties.MerchantConfig merchant(String id, long refId, String name) {
            return new MerchantsProperties.MerchantConfig(
                    id, refId, name, URI.create("http://merchant:8080"),
                    Duration.ofSeconds(1), Duration.ofSeconds(3));
        }
    }

    @Test
    @DisplayName("같은 이름의 상품은 merchant가 달라도 한 비교 묶음으로 합쳐진다")
    void search_groupsSameNameAcrossMerchants() throws Exception {
        when(productService.searchForInternal(any(), any(), any(), any())).thenReturn(new CatalogSearchResult(
                List.of(
                        item("merchant-a", "Copenhagen Demo Store", "prod-1001", "갤럭시 S25", 1200000L),
                        new CatalogSearchItem("merchant-b", "노을상점", "b-9", "갤럭시 S25", 1150000L,
                                "KRW", "IN_STOCK", "https://cdn.daisomall.co.kr/test.jpg"),
                        item("merchant-a", "Copenhagen Demo Store", "prod-1002", "아이폰 17", 1500000L)),
                List.of()));

        String body = mockMvc.perform(get("/internal/v1/catalog/products").param("query", "폰"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[0].offers.length()").value(2))
                .andExpect(jsonPath("$.products[0].brand").doesNotExist())
                .andExpect(jsonPath("$.products[0].imageUrl").value("https://cdn.daisomall.co.kr/test.jpg"))
                .andExpect(jsonPath("$.products[0].category.id").value("all"))
                .andExpect(jsonPath("$.filters.merchants.length()").value(2))
                .andExpect(jsonPath("$.checkedAt").value(NOW.toString()))
                .andReturn().getResponse().getContentAsString();

        McpContractAssertions.assertSearch(objectMapper.readValue(body, JSON_OBJECT));
    }

    @Test
    @DisplayName("다음 페이지가 없으면 nextCursor는 null이지만 키는 남는다")
    void search_keepsNextCursorKeyWhenNull() throws Exception {
        when(productService.searchForInternal(any(), any(), any(), any()))
                .thenReturn(new CatalogSearchResult(
                        List.of(item("merchant-a", "Copenhagen Demo Store", "p-1", "상품", 1000L)), List.of()));

        String body = mockMvc.perform(get("/internal/v1/catalog/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> parsed = objectMapper.readValue(body, JSON_OBJECT);
        if (!parsed.containsKey("nextCursor") || parsed.get("nextCursor") != null) {
            throw new AssertionError("nextCursor 키는 남고 값은 null이어야 한다: " + body);
        }
        McpContractAssertions.assertSearch(parsed);
    }

    @Test
    @DisplayName("커서로 다음 페이지를 이어서 받는다")
    void search_paginatesWithCursor() throws Exception {
        when(productService.searchForInternal(any(), any(), any(), any())).thenReturn(new CatalogSearchResult(
                List.of(
                        item("merchant-a", "Copenhagen Demo Store", "p-1", "상품1", 1000L),
                        item("merchant-a", "Copenhagen Demo Store", "p-2", "상품2", 2000L),
                        item("merchant-a", "Copenhagen Demo Store", "p-3", "상품3", 3000L)),
                List.of()));

        String firstPage = mockMvc.perform(get("/internal/v1/catalog/products").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[0].name").value("상품1"))
                .andReturn().getResponse().getContentAsString();
        String cursor = (String) objectMapper.readValue(firstPage, JSON_OBJECT).get("nextCursor");

        mockMvc.perform(get("/internal/v1/catalog/products").param("pageSize", "2").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].name").value("상품3"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("PRICE_ASC는 묶음의 최저가 offer 기준으로 정렬한다")
    void search_sortsByLowestOfferPrice() throws Exception {
        when(productService.searchForInternal(any(), any(), any(), any())).thenReturn(new CatalogSearchResult(
                List.of(
                        item("merchant-a", "Copenhagen Demo Store", "p-1", "비싼상품", 5000L),
                        item("merchant-a", "Copenhagen Demo Store", "p-2", "싼상품", 3000L),
                        item("merchant-b", "노을상점", "b-1", "비싼상품", 1000L)),
                List.of()));

        mockMvc.perform(get("/internal/v1/catalog/products").param("sort", "PRICE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].name").value("비싼상품"))
                .andExpect(jsonPath("$.products[1].name").value("싼상품"));
    }

    @Test
    @DisplayName("일부 merchant 호출이 실패하면 failedMerchants로 남긴다")
    void search_keepsFailedMerchants() throws Exception {
        when(productService.searchForInternal(any(), any(), any(), any())).thenReturn(new CatalogSearchResult(
                List.of(item("merchant-a", "Copenhagen Demo Store", "p-1", "상품", 1000L)),
                List.of("merchant-b")));

        String body = mockMvc.perform(get("/internal/v1/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedMerchants[0]").value("merchant-b"))
                .andReturn().getResponse().getContentAsString();

        McpContractAssertions.assertSearch(objectMapper.readValue(body, JSON_OBJECT));
    }

    @Test
    @DisplayName("all 이외의 카테고리나 브랜드 필터는 빈 결과다")
    void search_withUnsupportedFilter_isEmpty() throws Exception {
        mockMvc.perform(get("/internal/v1/catalog/products").param("categoryId", "phones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(0));

        mockMvc.perform(get("/internal/v1/catalog/products").param("brand", "삼성"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(0));
    }

    @Test
    @DisplayName("merchant가 모르는 availability는 UNKNOWN으로 낮춘다")
    void search_mapsUnknownAvailability() throws Exception {
        when(productService.searchForInternal(any(), any(), any(), any())).thenReturn(new CatalogSearchResult(
                List.of(new CatalogSearchItem(
                        "merchant-a", "Copenhagen Demo Store", "p-1", "상품", 1000L, "KRW", "BACKORDER")),
                List.of()));

        mockMvc.perform(get("/internal/v1/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].offers[0].availability").value("UNKNOWN"));
    }

    @Test
    @DisplayName("잘못된 커서는 400 INVALID_ARGUMENT")
    void search_withBrokenCursor_isInvalidArgument() throws Exception {
        mockMvc.perform(get("/internal/v1/catalog/products").param("cursor", "***"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("허용 범위를 벗어난 pageSize는 400")
    void search_withTooLargePageSize_isInvalidArgument() throws Exception {
        mockMvc.perform(get("/internal/v1/catalog/products").param("pageSize", "31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("상세는 요청한 merchantId·merchantProductId를 그대로 echo하고 옵션마다 variant를 만든다")
    void detail_echoesRequestAndBuildsVariants() throws Exception {
        when(productService.detailForInternal("merchant-a", "prod-1001")).thenReturn(new CatalogProductView(
                "merchant-a", "prod-1001", "갤럭시 S25", 1200000L, "KRW", "IN_STOCK",
                List.of(new CatalogProductView.OptionGroup("색상", List.of(
                        new CatalogProductView.OptionValue("sku-1001-black", "블랙"),
                        new CatalogProductView.OptionValue("sku-1001-white", "화이트")))),
                "https://cdn.daisomall.co.kr/test.jpg"));

        String body = mockMvc.perform(get("/internal/v1/merchants/merchant-a/products/prod-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value("merchant-a"))
                .andExpect(jsonPath("$.merchantProductId").value("prod-1001"))
                .andExpect(jsonPath("$.imageUrl").value("https://cdn.daisomall.co.kr/test.jpg"))
                .andExpect(jsonPath("$.optionGroups[0].values.length()").value(2))
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].merchantVariantId").value("sku-1001-black"))
                .andExpect(jsonPath("$.variants[0].maxQuantity").value(99))
                .andExpect(jsonPath("$.variants[0].stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.shipping.status").value("UNKNOWN"))
                .andReturn().getResponse().getContentAsString();

        McpContractAssertions.assertDetail(objectMapper.readValue(body, JSON_OBJECT));
    }

    @Test
    @DisplayName("옵션이 없는 상품은 optionGroups·variants가 빈 배열이다")
    void detail_withoutOptions_hasEmptyArrays() throws Exception {
        when(productService.detailForInternal("merchant-a", "prod-2002")).thenReturn(new CatalogProductView(
                "merchant-a", "prod-2002", "단일상품", 5000L, "KRW", "IN_STOCK", List.of()));

        String body = mockMvc.perform(get("/internal/v1/merchants/merchant-a/products/prod-2002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optionGroups.length()").value(0))
                .andExpect(jsonPath("$.variants.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        McpContractAssertions.assertDetail(objectMapper.readValue(body, JSON_OBJECT));
    }

    @Test
    @DisplayName("없는 상품은 404 PRODUCT_NOT_FOUND")
    void detail_notFound_isProductNotFound() throws Exception {
        when(productService.detailForInternal(eq("merchant-a"), eq("missing")))
                .thenThrow(new CoreException(CoreErrorCode.PRODUCT_NOT_FOUND));

        mockMvc.perform(get("/internal/v1/merchants/merchant-a/products/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    @DisplayName("merchant Catalog 장애는 503 CORE_SERVICE_ERROR")
    void detail_merchantUnavailable_isCoreServiceError() throws Exception {
        when(productService.detailForInternal(eq("merchant-a"), eq("prod-1001")))
                .thenThrow(new CoreException(CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE));

        mockMvc.perform(get("/internal/v1/merchants/merchant-a/products/prod-1001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CORE_SERVICE_ERROR"));
    }

    private static CatalogSearchItem item(
            String merchantId, String merchantName, String productId, String name, long price) {
        return new CatalogSearchItem(merchantId, merchantName, productId, name, price, "KRW", "IN_STOCK");
    }
}
