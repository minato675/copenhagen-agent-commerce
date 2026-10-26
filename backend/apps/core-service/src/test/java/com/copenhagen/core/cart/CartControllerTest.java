package com.copenhagen.core.cart;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.auth.CoreAuthenticationFilter;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.common.error.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code CoreAuthenticationFilter} 제외 이유는 {@code AddressControllerTest}와 같다. */
@WebMvcTest(
        controllers = CartController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class CartControllerTest {

    private static final long USER_ID = 42L;
    private static final UUID PRINCIPAL = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("조회는 cartId·version·items·summary를 그대로 돌려준다")
    void get_returnsCartView() throws Exception {
        stubPrincipalUser();
        UUID cartId = UUID.randomUUID();
        UUID cartItemId = UUID.randomUUID();
        when(cartService.get(USER_ID)).thenReturn(new CartView(
                cartId, 2,
                List.of(new CartView.CartItemView(
                        cartItemId, "merchant-a", "p-1", List.of("sku-1"), "옵션상품",
                        "http://merchant-a:8080/products/p-1", null, 2,
                        new CartView.Price(5000L, "KRW"), 10000L)),
                new CartView.CartSummary(10000L, 0L, 0L, 10000L, "KRW")));

        mockMvc.perform(get("/api/core/v1/cart").with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value(cartId.toString()))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.items[0].cartItemId").value(cartItemId.toString()))
                .andExpect(jsonPath("$.items[0].optionValueIds[0]").value("sku-1"))
                .andExpect(jsonPath("$.items[0].lineTotal").value(10000))
                .andExpect(jsonPath("$.summary.shippingFee").value(0))
                .andExpect(jsonPath("$.summary.estimatedTotal").value(10000));
    }

    @Test
    @DisplayName("담기에 성공하면 201과 cartItemId·cartVersion을 반환한다")
    void add_valid_returns201() throws Exception {
        stubPrincipalUser();
        UUID cartItemId = UUID.randomUUID();
        when(cartService.add(eq(USER_ID), eq(0), eq("merchant-a"), eq("p-1"), eq(List.of("sku-1")), eq(2)))
                .thenReturn(new CartAddResult(cartItemId, 1));

        mockMvc.perform(post("/api/core/v1/cart/items")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cartItemId").value(cartItemId.toString()))
                .andExpect(jsonPath("$.cartVersion").value(1));
    }

    @Test
    @DisplayName("optionValueIds는 선택 파라미터라 없어도 담기가 진행된다")
    void add_withoutOptionValueIds_passesEmptyList() throws Exception {
        stubPrincipalUser();
        UUID cartItemId = UUID.randomUUID();
        when(cartService.add(eq(USER_ID), eq(0), eq("merchant-a"), eq("p-1"), eq(List.of()), eq(1)))
                .thenReturn(new CartAddResult(cartItemId, 1));

        mockMvc.perform(post("/api/core/v1/cart/items")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":0,"productId":"p-1","merchantId":"merchant-a","quantity":1}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("수량이 0 이하면 400 INVALID_REQUEST다")
    void add_nonPositiveQuantity_returns400() throws Exception {
        stubPrincipalUser();

        mockMvc.perform(post("/api/core/v1/cart/items")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":0,"productId":"p-1","merchantId":"merchant-a","quantity":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("버전 충돌은 409와 nextAction=REFETCH_CART로 내려간다")
    void add_versionConflict_returns409WithRefetchCart() throws Exception {
        stubPrincipalUser();
        when(cartService.add(anyLong(), anyInt(), any(), any(), any(), anyInt()))
                .thenThrow(new CoreException(CoreErrorCode.CART_VERSION_CONFLICT));

        mockMvc.perform(post("/api/core/v1/cart/items")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CART_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.nextAction").value("REFETCH_CART"));
    }

    @Test
    @DisplayName("옵션 미선택은 400 CART_OPTION_REQUIRED와 nextAction=SELECT_OPTION이다")
    void add_optionRequired_returns400() throws Exception {
        stubPrincipalUser();
        when(cartService.add(anyLong(), anyInt(), any(), any(), any(), anyInt()))
                .thenThrow(new CoreException(CoreErrorCode.CART_OPTION_REQUIRED));

        mockMvc.perform(post("/api/core/v1/cart/items")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CART_OPTION_REQUIRED"))
                .andExpect(jsonPath("$.nextAction").value("SELECT_OPTION"));
    }

    @Test
    @DisplayName("수량 변경은 새 cartVersion을 반환한다")
    void updateQuantity_returnsNewVersion() throws Exception {
        stubPrincipalUser();
        UUID cartItemId = UUID.randomUUID();
        when(cartService.updateQuantity(USER_ID, cartItemId, 3, 5)).thenReturn(4);

        mockMvc.perform(patch("/api/core/v1/cart/items/" + cartItemId)
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":3,"quantity":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartVersion").value(4));
    }

    @Test
    @DisplayName("없는 항목을 수정하면 404 CART_ITEM_NOT_FOUND다")
    void updateQuantity_missingItem_returns404() throws Exception {
        stubPrincipalUser();
        UUID cartItemId = UUID.randomUUID();
        when(cartService.updateQuantity(anyLong(), eq(cartItemId), anyInt(), anyInt()))
                .thenThrow(new CoreException(CoreErrorCode.CART_ITEM_NOT_FOUND));

        mockMvc.perform(patch("/api/core/v1/cart/items/" + cartItemId)
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":3,"quantity":5}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CART_ITEM_NOT_FOUND"));
    }

    @Test
    @DisplayName("삭제는 cartVersion 쿼리 파라미터를 받고 새 버전을 반환한다")
    void remove_returnsNewVersion() throws Exception {
        stubPrincipalUser();
        UUID cartItemId = UUID.randomUUID();
        when(cartService.remove(USER_ID, cartItemId, 4)).thenReturn(5);

        mockMvc.perform(delete("/api/core/v1/cart/items/" + cartItemId)
                        .param("cartVersion", "4")
                        .with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartVersion").value(5));
    }

    @Test
    @DisplayName("필터를 거치지 않아 principal이 없으면 401 AUTH_TOKEN_MISSING을 반환한다")
    void get_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/core/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_MISSING"));
    }

    private void stubPrincipalUser() {
        when(userRepository.findByPublicId(PRINCIPAL)).thenReturn(Optional.of(user()));
    }

    private MockHttpServletRequest withPrincipal(MockHttpServletRequest request) {
        CoreRequestContext.setPrincipal(request, PRINCIPAL);
        return request;
    }

    private static String addRequestBody() {
        return """
                {"cartVersion":0,"productId":"p-1","merchantId":"merchant-a",
                 "optionValueIds":["sku-1"],"quantity":2}
                """;
    }

    private static User user() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new User(
                USER_ID, PRINCIPAL, "홍길동", UserStatus.ACTIVE,
                EncryptedPii.of(new byte[] {1}), "email-hash", true,
                null, null, null, null, null, now, now);
    }
}
