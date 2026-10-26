package com.copenhagen.core.cart;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.product.CartProductSnapshot;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.product.ProductService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER_ID = 7L;
    private static final long CART_ID = 100L;
    private static final long MERCHANT_REF_ID = 1L;
    private static final UUID CART_PUBLIC_ID = UUID.randomUUID();

    private CartRepository cartRepository;
    private ProductService productService;
    private CartService service;

    @BeforeEach
    void setUp() {
        cartRepository = mock(CartRepository.class);
        productService = mock(ProductService.class);
        MerchantsProperties properties = new MerchantsProperties(List.of(
                new MerchantsProperties.MerchantConfig(
                        "merchant-a", MERCHANT_REF_ID, "A몰", URI.create("http://merchant-a:8080"),
                        Duration.ofSeconds(1), Duration.ofSeconds(3))));
        service = new CartService(
                cartRepository,
                productService,
                new CartMerchants(properties),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("장바구니가 없으면 조회는 cartId null·version 0·빈 합계를 돌려준다")
    void get_noCart_returnsEmptyView() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

        CartView view = service.get(USER_ID);

        assertThat(view.cartId()).isNull();
        assertThat(view.version()).isZero();
        assertThat(view.items()).isEmpty();
        assertThat(view.summary().estimatedTotal()).isZero();
        assertThat(view.summary().currency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("조회는 항목 합계를 계산하고 할인·배송비는 0으로 둔다")
    void get_withItems_sumsItemsTotalOnly() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(cart(3)));
        when(cartRepository.findItemsByCartId(CART_ID)).thenReturn(List.of(item(2, 5000L)));

        CartView view = service.get(USER_ID);

        assertThat(view.cartId()).isEqualTo(CART_PUBLIC_ID);
        assertThat(view.version()).isEqualTo(3);
        assertThat(view.items()).hasSize(1);
        CartView.CartItemView itemView = view.items().get(0);
        assertThat(itemView.merchantId()).isEqualTo("merchant-a");
        assertThat(itemView.optionValueIds()).containsExactly("sku-1");
        assertThat(itemView.lineTotal()).isEqualTo(10000L);
        assertThat(view.summary().itemsTotal()).isEqualTo(10000L);
        assertThat(view.summary().discountTotal()).isZero();
        assertThat(view.summary().shippingFee()).isZero();
        assertThat(view.summary().estimatedTotal()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("장바구니가 없을 때 cartVersion 0으로 담으면 장바구니가 생기고 버전이 1이 된다")
    void add_noCart_createsCartAtVersionOne() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(cartRepository.insertActiveCart(any(), eq(USER_ID), eq(NOW)))
                .thenAnswer(invocation -> new Cart(CART_ID, invocation.getArgument(0), USER_ID, 1));
        when(productService.captureForCart("merchant-a", "p-1", List.of("sku-1"))).thenReturn(snapshot());
        when(cartRepository.findItemByProductVariant(CART_ID, 1L, "p-1", "sku-1")).thenReturn(Optional.empty());

        CartAddResult result = service.add(USER_ID, 0, "merchant-a", "p-1", List.of("sku-1"), 2);

        assertThat(result.cartVersion()).isEqualTo(1);
        assertThat(result.cartItemId()).isNotNull();
        verify(cartRepository).insertItem(eq(result.cartItemId()), any(), eq(NOW));
        verify(cartRepository, never()).bumpVersion(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("장바구니가 없는데 cartVersion이 0이 아니면 재조회를 요구한다")
    void add_noCart_nonZeroVersion_conflicts() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());
        when(productService.captureForCart("merchant-a", "p-1", List.of("sku-1"))).thenReturn(snapshot());

        assertThatThrownBy(() -> service.add(USER_ID, 5, "merchant-a", "p-1", List.of("sku-1"), 1))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.CART_VERSION_CONFLICT);
    }

    @Test
    @DisplayName("버전이 맞으면 새 항목을 담고 버전이 1 오른다")
    void add_existingCart_insertsItemAndBumpsVersion() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(cart(3)));
        when(cartRepository.bumpVersion(CART_ID, 3, NOW)).thenReturn(true);
        when(productService.captureForCart("merchant-a", "p-1", List.of("sku-1"))).thenReturn(snapshot());
        when(cartRepository.findItemByProductVariant(CART_ID, 1L, "p-1", "sku-1")).thenReturn(Optional.empty());

        CartAddResult result = service.add(USER_ID, 3, "merchant-a", "p-1", List.of("sku-1"), 2);

        assertThat(result.cartVersion()).isEqualTo(4);
        verify(cartRepository).insertItem(eq(result.cartItemId()), any(), eq(NOW));
    }

    @Test
    @DisplayName("버전이 어긋나면 CART_VERSION_CONFLICT이고 항목은 건드리지 않는다")
    void add_versionMismatch_conflicts() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(cart(3)));
        when(cartRepository.bumpVersion(CART_ID, 2, NOW)).thenReturn(false);
        when(productService.captureForCart("merchant-a", "p-1", List.of("sku-1"))).thenReturn(snapshot());

        assertThatThrownBy(() -> service.add(USER_ID, 2, "merchant-a", "p-1", List.of("sku-1"), 1))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.CART_VERSION_CONFLICT);
        verify(cartRepository, never()).insertItem(any(), any(), any());
    }

    @Test
    @DisplayName("같은 상품·옵션을 다시 담으면 수량을 합산하고 기존 cartItemId를 돌려준다")
    void add_sameProductAndOption_mergesQuantity() {
        CartItem existing = item(2, 5000L);
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(cart(3)));
        when(cartRepository.bumpVersion(CART_ID, 3, NOW)).thenReturn(true);
        when(productService.captureForCart("merchant-a", "p-1", List.of("sku-1"))).thenReturn(snapshot());
        when(cartRepository.findItemByProductVariant(CART_ID, 1L, "p-1", "sku-1"))
                .thenReturn(Optional.of(existing));

        CartAddResult result = service.add(USER_ID, 3, "merchant-a", "p-1", List.of("sku-1"), 3);

        assertThat(result.cartItemId()).isEqualTo(existing.publicId());
        assertThat(result.cartVersion()).isEqualTo(4);
        verify(cartRepository).updateItemOnMerge(
                eq(existing.id()),
                org.mockito.ArgumentMatchers.argThat(row -> row.quantity() == 5),
                eq(NOW));
        verify(cartRepository, never()).insertItem(any(), any(), any());
    }

    @Test
    @DisplayName("설정에 없는 merchantId는 INVALID_REQUEST다")
    void add_unknownMerchant_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.add(USER_ID, 0, "merchant-z", "p-1", List.of("sku-1"), 1))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("수량 변경은 버전을 올리고 Catalog를 다시 호출하지 않는다")
    void updateQuantity_success_bumpsVersion() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(cart(3)));
        when(cartRepository.bumpVersion(CART_ID, 3, NOW)).thenReturn(true);
        UUID cartItemId = UUID.randomUUID();
        when(cartRepository.updateItemQuantity(cartItemId, CART_ID, 5, NOW)).thenReturn(1);

        assertThat(service.updateQuantity(USER_ID, cartItemId, 3, 5)).isEqualTo(4);
        verify(productService, never()).captureForCart(any(), any(), any());
    }

    @Test
    @DisplayName("남의 항목이거나 없는 항목이면 CART_ITEM_NOT_FOUND다")
    void updateQuantity_unknownItem_throwsNotFound() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(cart(3)));
        when(cartRepository.bumpVersion(CART_ID, 3, NOW)).thenReturn(true);
        UUID cartItemId = UUID.randomUUID();
        when(cartRepository.updateItemQuantity(cartItemId, CART_ID, 5, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.updateQuantity(USER_ID, cartItemId, 3, 5))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제는 버전을 올리고 항목을 지운다")
    void remove_success_bumpsVersion() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(cart(4)));
        when(cartRepository.bumpVersion(CART_ID, 4, NOW)).thenReturn(true);
        UUID cartItemId = UUID.randomUUID();
        when(cartRepository.deleteItem(cartItemId, CART_ID)).thenReturn(1);

        assertThat(service.remove(USER_ID, cartItemId, 4)).isEqualTo(5);
    }

    @Test
    @DisplayName("장바구니가 없는데 변경을 요청하면 재조회를 요구한다")
    void remove_noCart_conflicts() {
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(USER_ID, UUID.randomUUID(), 1))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.CART_VERSION_CONFLICT);
    }

    private static Cart cart(int version) {
        return new Cart(CART_ID, CART_PUBLIC_ID, USER_ID, version);
    }

    private static CartItem item(int quantity, long unitPrice) {
        return new CartItem(
                55L, UUID.randomUUID(), CART_ID, MERCHANT_REF_ID, "p-1", "sku-1", "옵션상품",
                "http://merchant-a:8080/products/p-1", null,
                "[{\"optionValueId\":\"sku-1\",\"name\":\"색상\",\"label\":\"화이트\"}]",
                quantity, unitPrice, "KRW");
    }

    private static CartProductSnapshot snapshot() {
        return new CartProductSnapshot(
                "p-1", "sku-1", "옵션상품", "http://merchant-a:8080/products/p-1", null, 5000L, "KRW",
                List.of(new CartProductSnapshot.SelectedOption("sku-1", "색상", "화이트")));
    }
}
