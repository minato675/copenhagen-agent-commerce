package com.copenhagen.core.cart;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartRepository {

    Optional<Cart> findActiveByUserId(long userId);

    /** ACTIVE 장바구니를 만든다. {@code version}은 1에서 시작한다({@code carts.version DEFAULT 1}). */
    Cart insertActiveCart(UUID publicId, long userId, Instant now);

    /**
     * 조건부 UPDATE로 버전을 올린다. 반환값이 {@code false}면 다른 요청이 먼저 바꾼 것이다(409).
     * 항목 변경보다 먼저 호출해 같은 장바구니에 대한 동시 요청을 행 잠금으로 직렬화한다.
     */
    boolean bumpVersion(long cartId, int expectedVersion, Instant now);

    Optional<CartItem> findItemByProductVariant(
            long cartId, long merchantId, String merchantProductId, String merchantVariantId);

    /** 소유권 검사를 쿼리 조건에 내장한다 — 다른 장바구니의 항목은 없는 것으로 취급한다. */
    Optional<CartItem> findItemByPublicId(UUID publicId, long cartId);

    void insertItem(UUID publicId, CartItem item, Instant now);

    /** 같은 상품·옵션을 다시 담았을 때 — 수량을 합산하고 상품 정보·가격을 다시 캡처한 값으로 덮는다. */
    void updateItemOnMerge(long itemId, CartItem item, Instant now);

    /**
     * 옵션(variant)을 바꿀 때 — {@code merchant_variant_id}까지 새 스냅샷으로 덮는다(순서 34).
     * {@code uq_cart_items_cart_product_variant} 때문에 바꾸려는 variant가 다른 줄에 이미 있으면
     * 이 메서드가 아니라 합치기({@link #updateItemOnMerge} + {@link #deleteItemById})를 써야 한다.
     */
    void updateItemVariant(long itemId, CartItem item, Instant now);

    /** 줄 합치기로 남지 않게 된 줄을 지운다(순서 34). */
    int deleteItemById(long itemId);

    /** 소유권 검사를 쿼리 조건에 내장한다 — 다른 사용자의 항목은 0행으로 취급한다. */
    int updateItemQuantity(UUID publicId, long cartId, int quantity, Instant now);

    int deleteItem(UUID publicId, long cartId);

    List<CartItem> findItemsByCartId(long cartId);
}
