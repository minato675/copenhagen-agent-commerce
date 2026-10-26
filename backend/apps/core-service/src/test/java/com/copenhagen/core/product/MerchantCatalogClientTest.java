package com.copenhagen.core.product;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MerchantCatalogClientTest {

    private RestClient.Builder builder = RestClient.builder();
    private MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private MerchantCatalogClient client = new MerchantCatalogClient(
            "merchant-a", "Copenhagen Demo Store", "http://merchant-a:8080", builder.build());

    @Test
    @DisplayName("merchantId·merchantName·baseUrl을 그대로 노출한다")
    void exposesMerchantIdentity() {
        assertThat(client.merchantId()).isEqualTo("merchant-a");
        assertThat(client.merchantName()).isEqualTo("Copenhagen Demo Store");
        assertThat(client.baseUrl()).isEqualTo("http://merchant-a:8080");
    }

    @Test
    @DisplayName("검색 성공 시 items를 그대로 반환한다")
    void search_success_returnsItems() {
        server.expect(requestTo("/ucp/v1/catalog/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"query":"셔츠","filters":{"maxPrice":10000,"currency":"KRW"}}
                        """))
                .andRespond(withSuccess("""
                        {"items":[{"productId":"p-1","title":"린넨 셔츠","imageUrl":"https://cdn.daisomall.co.kr/test.jpg",
                          "price":{"amount":9900,"currency":"KRW"},"availability":"IN_STOCK"}]}
                        """, MediaType.APPLICATION_JSON));

        List<MerchantCatalogClient.CatalogItem> items = client.search("셔츠", 10_000L, "KRW");

        assertThat(items).containsExactly(new MerchantCatalogClient.CatalogItem(
                "p-1", "린넨 셔츠", new MerchantCatalogClient.CatalogPrice(9900, "KRW"), "IN_STOCK",
                "https://cdn.daisomall.co.kr/test.jpg"));
        server.verify();
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 리스트를 반환한다")
    void search_empty_returnsEmptyList() {
        server.expect(requestTo("/ucp/v1/catalog/search"))
                .andRespond(withSuccess("""
                        {"items":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.search("없는상품", null, null)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("상세 조회 성공 시 응답을 그대로 반환한다")
    void product_success_returnsDetail() {
        server.expect(requestTo("/ucp/v1/catalog/product"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"productId":"p-1","title":"린넨 셔츠","description":"여름용",
                          "imageUrl":"https://cdn.daisomall.co.kr/test.jpg",
                          "price":{"amount":9900,"currency":"KRW"},
                          "options":[{"name":"색상","values":[{"optionValueId":"sku-1","label":"화이트"}]}],
                          "availability":"IN_STOCK"}
                        """, MediaType.APPLICATION_JSON));

        MerchantCatalogClient.CatalogProductDetail detail = client.product("p-1");

        assertThat(detail.productId()).isEqualTo("p-1");
        assertThat(detail.imageUrl()).isEqualTo("https://cdn.daisomall.co.kr/test.jpg");
        assertThat(detail.options()).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("존재하지 않는 상품은 PRODUCT_NOT_FOUND로 변환한다")
    void product_notFound_throwsProductNotFound() {
        server.expect(requestTo("/ucp/v1/catalog/product"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"errorCode":"PRODUCT_NOT_FOUND","message":"없음",
                                 "currentState":null,"nextAction":"CHECK_REQUEST"}
                                """));

        assertThatThrownBy(() -> client.product("unknown"))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.PRODUCT_NOT_FOUND);
        server.verify();
    }

    @Test
    @DisplayName("Merchant 서버 오류는 MERCHANT_CATALOG_UNAVAILABLE로 변환한다")
    void search_serverError_throwsMerchantCatalogUnavailable() {
        server.expect(requestTo("/ucp/v1/catalog/search")).andRespond(withServerError());

        assertThatThrownBy(() -> client.search("셔츠", null, null))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE);
        server.verify();
    }
}
