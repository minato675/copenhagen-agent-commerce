package com.copenhagen.merchantmock.catalog;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import com.copenhagen.merchantmock.support.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Catalog 세 엔드포인트의 HTTP 계약. DB 없이 standalone MockMvc 로 돌린다. */
class CatalogControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CatalogStore store = new CatalogStore(CatalogFixture::seed);
        store.load();
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new CatalogController(new CatalogService(store, MerchantPropertiesFixture.defaults())))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("검색이 items 를 돌려준다")
    void search_matchingQuery_returnsItems() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/catalog/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"러닝화\",\"filters\":{\"maxPrice\":200000,\"currency\":\"KRW\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value("product-101"))
                .andExpect(jsonPath("$.items[0].title").value("러닝화 A"))
                .andExpect(jsonPath("$.items[0].price.amount").value(89000))
                .andExpect(jsonPath("$.items[0].price.currency").value("KRW"))
                .andExpect(jsonPath("$.items[0].availability").value("IN_STOCK"));
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 배열이고 200 이다")
    void search_noMatch_returnsEmptyItemsWith200() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/catalog/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"없는상품\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("일괄조회가 요청한 순서로 돌려준다")
    void lookup_multipleIds_keepsRequestedOrder() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/catalog/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"product-104\",\"product-101\",\"product-999\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value("product-104"))
                .andExpect(jsonPath("$.items[1].productId").value("product-101"));
    }

    @Test
    @DisplayName("상세조회가 옵션까지 돌려준다")
    void product_existingId_returnsDetailWithOptions() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/catalog/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"product-102\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value("product-102"))
                .andExpect(jsonPath("$.price.amount").value(129000))
                .andExpect(jsonPath("$.options[0].name").value("size"))
                .andExpect(jsonPath("$.options[0].values.length()").value(3))
                .andExpect(jsonPath("$.options[0].values[0].optionValueId").value("product-102-m-black"))
                .andExpect(jsonPath("$.availability").value("IN_STOCK"));
    }

    @Test
    @DisplayName("없는 상품은 404 와 오류 Envelope 이다")
    void product_unknownId_returns404WithErrorEnvelope() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/catalog/product")
                        .header("X-Request-Id", "req-test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"product-999\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.nextAction").value("RETURN_TO_CART"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("단종 상품도 404 다")
    void product_discontinuedId_returns404() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/catalog/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"product-105\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
    }
}
