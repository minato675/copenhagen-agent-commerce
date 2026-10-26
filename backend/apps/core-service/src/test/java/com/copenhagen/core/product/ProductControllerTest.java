package com.copenhagen.core.product;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.common.auth.CoreAuthenticationFilter;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.error.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code CoreAuthenticationFilter} 제외 이유는 {@code AddressControllerTest}와 같다. */
@WebMvcTest(
        controllers = ProductController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class ProductControllerTest {

    private static final UUID PRINCIPAL = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    @DisplayName("검색 요청이 유효하면 200과 envelope를 반환한다")
    void search_valid_returns200() throws Exception {
        SearchData data = new SearchData("catalog.search", "LIVE", "OK",
                new SearchQuery("셔츠", "", "", "", null, null, 1, 4, "RELEVANCE"),
                List.of(new CatalogProduct("p-1", "린넨 셔츠", "", "", "",
                        List.of(new Offer("merchant-a", "A몰", 9900L, "KRW", "IN_STOCK")))),
                List.of(), List.of(new MerchantRef("merchant-a", "A몰")), List.of(), 1, false, "2026-01-01T00:00:00Z");
        when(productService.search(eq(PRINCIPAL), any())).thenReturn(
                new SearchEnvelope(PRINCIPAL.toString(), "default", data));

        mockMvc.perform(post("/api/core/v1/products/search")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"셔츠"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products[0].productId").value("p-1"))
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.connectionRef").value("default"));
    }

    @Test
    @DisplayName("필드를 생략하면 page=1/pageSize=4/sort=RELEVANCE 기본값으로 조회한다")
    void search_defaultsApplied() throws Exception {
        when(productService.search(eq(PRINCIPAL), eq(
                new SearchQuery("셔츠", "", "", "", null, null, 1, 4, "RELEVANCE"))))
                .thenReturn(new SearchEnvelope(PRINCIPAL.toString(), "default",
                        new SearchData("catalog.search", "LIVE", "EMPTY",
                                new SearchQuery("셔츠", "", "", "", null, null, 1, 4, "RELEVANCE"),
                                List.of(), List.of(), List.of(), List.of(), 0, false, "2026-01-01T00:00:00Z")));

        mockMvc.perform(post("/api/core/v1/products/search")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"셔츠"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EMPTY"));
    }

    @Test
    @DisplayName("존재하지 않는 상품 조회는 200과 NOT_FOUND 상태를 반환한다")
    void get_notFound_returns200WithStatus() throws Exception {
        DetailData data = new DetailData("catalog.detail", "LIVE", "NOT_FOUND",
                new DetailRequestEcho("unknown", "merchant-a", null), null, "2026-01-01T00:00:00Z");
        when(productService.getDetail(PRINCIPAL, "unknown", "merchant-a", ""))
                .thenReturn(new DetailEnvelope(PRINCIPAL.toString(), "default", data));

        mockMvc.perform(get("/api/core/v1/products/unknown")
                        .param("merchantId", "merchant-a")
                        .with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data.product").doesNotExist());
    }

    @Test
    @DisplayName("필터를 거치지 않아 principal이 없으면 401 AUTH_TOKEN_MISSING을 반환한다")
    void search_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(post("/api/core/v1/products/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"셔츠"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_MISSING"));
    }

    private MockHttpServletRequest withPrincipal(MockHttpServletRequest request) {
        CoreRequestContext.setPrincipal(request, PRINCIPAL);
        return request;
    }
}
