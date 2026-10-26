package com.copenhagen.core.cart;

import java.util.List;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.auth.signup.SignupService;
import com.copenhagen.core.product.CartProductSnapshot;
import com.copenhagen.core.product.ProductService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 순서 25(통합 장바구니) — 실 PostgreSQL로 담기/조회/수량변경/삭제 흐름과 버전 충돌·소유권 검사를 확인한다.
 *
 * <p>Merchant Catalog 호출만 {@link ProductService}를 대역으로 끊는다(HTTP 의존을 없애기 위해서다).
 * 나머지(Flyway, 인증, 트랜잭션, 제약조건)는 전부 실제로 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class CartApiIntegrationTest {

    private static final String PASSWORD = "Password1!";

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignupService signupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProductService productService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("TRUNCATE users, carts, cart_items RESTART IDENTITY CASCADE");
        // merchants 는 V7 시드다. 다른 통합 테스트가 TRUNCATE 하고 갈 수 있어 멱등하게 되살린다.
        jdbcTemplate.update("""
                INSERT INTO merchants (id, merchant_name, ucp_profile_url, base_url, business_profile,
                                       active_capabilities, status)
                VALUES (1, 'Copenhagen Demo Store', 'http://ucp-merchant-mock:8080/.well-known/ucp',
                        'http://ucp-merchant-mock:8080', '{}'::jsonb, '[]'::jsonb, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """);
        when(productService.captureForCart(eq("merchant-a"), eq("p-1"), any()))
                .thenReturn(snapshot("p-1", "sku-1", "옵션상품", 5000L));
        when(productService.captureForCart(eq("merchant-a"), eq("p-2"), any()))
                .thenReturn(snapshot("p-2", "sku-2", "다른상품", 3000L));
    }

    @Test
    @DisplayName("V7 마이그레이션이 적용돼 merchants 시드가 있다")
    void migration_v7_isApplied() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success = TRUE",
                Integer.class);
        assertEquals(1, applied, "V7__seed_merchants.sql 이 적용돼야 한다");
    }

    @Test
    @DisplayName("담기→조회→수량변경→삭제 전체 흐름에서 cartVersion이 1씩 오른다")
    void cartLifecycle_fullFlow_success() throws Exception {
        String accessToken = signupAndLogin("cartflow1", "홍길동");

        // 장바구니가 없는 사용자는 cartId null, version 0 이다.
        mockMvc.perform(get("/api/core/v1/cart").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").doesNotExist())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.summary.estimatedTotal").value(0));

        String cartItemId = add(accessToken, 0, "p-1", 2);

        mockMvc.perform(get("/api/core/v1/cart").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].cartItemId").value(cartItemId))
                .andExpect(jsonPath("$.items[0].merchantId").value("merchant-a"))
                .andExpect(jsonPath("$.items[0].optionValueIds[0]").value("sku-1"))
                .andExpect(jsonPath("$.items[0].lineTotal").value(10000))
                .andExpect(jsonPath("$.summary.itemsTotal").value(10000))
                .andExpect(jsonPath("$.summary.discountTotal").value(0))
                .andExpect(jsonPath("$.summary.shippingFee").value(0))
                .andExpect(jsonPath("$.summary.estimatedTotal").value(10000));

        mockMvc.perform(patch("/api/core/v1/cart/items/" + cartItemId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":1,"quantity":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartVersion").value(2));

        mockMvc.perform(delete("/api/core/v1/cart/items/" + cartItemId)
                        .param("cartVersion", "2")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartVersion").value(3));

        mockMvc.perform(get("/api/core/v1/cart").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.summary.estimatedTotal").value(0));
    }

    @Test
    @DisplayName("같은 상품·옵션을 다시 담으면 한 줄로 합산된다")
    void add_sameProductAndOption_mergesIntoOneRow() throws Exception {
        String accessToken = signupAndLogin("cartmerge1", "홍길동");
        String first = add(accessToken, 0, "p-1", 2);
        String second = add(accessToken, 1, "p-1", 3);

        assertEquals(first, second, "같은 상품·옵션은 같은 cartItemId 여야 한다");
        mockMvc.perform(get("/api/core/v1/cart").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.summary.itemsTotal").value(25000));
    }

    @Test
    @DisplayName("다른 상품은 별도 항목으로 담기고 합계에 함께 잡힌다")
    void add_differentProduct_addsAnotherRow() throws Exception {
        String accessToken = signupAndLogin("cartmulti1", "홍길동");
        add(accessToken, 0, "p-1", 1);
        add(accessToken, 1, "p-2", 2);

        mockMvc.perform(get("/api/core/v1/cart").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.summary.itemsTotal").value(11000));
    }

    @Test
    @DisplayName("버전이 어긋난 요청은 409 CART_VERSION_CONFLICT이고 장바구니는 그대로다")
    void add_staleVersion_returns409AndKeepsCart() throws Exception {
        String accessToken = signupAndLogin("cartstale1", "홍길동");
        add(accessToken, 0, "p-1", 1);

        mockMvc.perform(post("/api/core/v1/cart/items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(0, "p-2", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CART_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.nextAction").value("REFETCH_CART"));

        mockMvc.perform(get("/api/core/v1/cart").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    @DisplayName("장바구니가 없는데 cartVersion을 0이 아닌 값으로 담으면 409다")
    void add_noCart_nonZeroVersion_returns409() throws Exception {
        String accessToken = signupAndLogin("cartfirst1", "홍길동");

        mockMvc.perform(post("/api/core/v1/cart/items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(1, "p-1", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CART_VERSION_CONFLICT"));
    }

    @Test
    @DisplayName("사용자마다 ACTIVE 장바구니는 1건이고 다른 사용자의 항목은 404다")
    void crossUserAccess_returns404AndKeepsOneActiveCartPerUser() throws Exception {
        String ownerToken = signupAndLogin("cartowner1", "홍길동");
        String otherToken = signupAndLogin("cartother1", "김철수");
        String cartItemId = add(ownerToken, 0, "p-1", 1);
        add(otherToken, 0, "p-2", 1);

        mockMvc.perform(patch("/api/core/v1/cart/items/" + cartItemId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":1,"quantity":9}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CART_ITEM_NOT_FOUND"));

        Integer activeCarts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE status = 'ACTIVE'", Integer.class);
        assertEquals(2, activeCarts, "사용자별로 ACTIVE 장바구니가 정확히 1건씩이어야 한다");
    }

    private String add(String accessToken, int cartVersion, String productId, int quantity) throws Exception {
        String response = mockMvc.perform(post("/api/core/v1/cart/items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(cartVersion, productId, quantity)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.cartItemId");
    }

    private static String addBody(int cartVersion, String productId, int quantity) {
        return """
                {"cartVersion":%d,"productId":"%s","merchantId":"merchant-a",
                 "optionValueIds":["sku-for-%s"],"quantity":%d}
                """.formatted(cartVersion, productId, productId, quantity);
    }

    private static CartProductSnapshot snapshot(
            String productId, String variantId, String name, long price) {
        return new CartProductSnapshot(
                productId, variantId, name, "http://ucp-merchant-mock:8080/products/" + productId,
                null, price, "KRW",
                List.of(new CartProductSnapshot.SelectedOption(variantId, "색상", "화이트")));
    }

    private String signupAndLogin(String loginId, String name) throws Exception {
        signupService.signup(loginId, PASSWORD, loginId + "@example.com", name);
        String body = """
                {"loginId":"%s","password":"%s","deviceId":"device-1","deviceName":"iPhone 15"}
                """.formatted(loginId, PASSWORD);
        String response = mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
