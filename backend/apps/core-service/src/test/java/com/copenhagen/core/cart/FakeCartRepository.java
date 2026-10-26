package com.copenhagen.core.cart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 인메모리 장바구니 저장소. 버전 조건부 UPDATE와 소유권 조건(cart_id)을 실제 SQL과 같은 규칙으로 흉내낸다.
 * {@code uq_cart_items_cart_product_variant}는 강제하지 않는다 — 서비스가 그 제약을 피하도록 짜였는지
 * 확인하는 것이 테스트의 목적이라 위반이 생기면 그대로 드러나는 편이 낫다.
 */
public class FakeCartRepository implements CartRepository {

    private final List<Cart> carts = new ArrayList<>();
    private final List<CartItem> items = new ArrayList<>();
    private long nextCartId = 1;
    private long nextItemId = 1;

    @Override
    public Optional<Cart> findActiveByUserId(long userId) {
        return carts.stream().filter(cart -> cart.userId() == userId).findFirst();
    }

    @Override
    public Cart insertActiveCart(UUID publicId, long userId, Instant now) {
        Cart cart = new Cart(nextCartId++, publicId, userId, 1);
        carts.add(cart);
        return cart;
    }

    @Override
    public boolean bumpVersion(long cartId, int expectedVersion, Instant now) {
        for (int index = 0; index < carts.size(); index++) {
            Cart cart = carts.get(index);
            if (cart.id() == cartId && cart.version() == expectedVersion) {
                carts.set(index, new Cart(cart.id(), cart.publicId(), cart.userId(), cart.version() + 1));
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<CartItem> findItemByProductVariant(
            long cartId, long merchantId, String merchantProductId, String merchantVariantId) {
        return items.stream()
                .filter(item -> item.cartId() == cartId
                        && item.merchantId() == merchantId
                        && item.merchantProductId().equals(merchantProductId)
                        && item.merchantVariantId().equals(merchantVariantId))
                .findFirst();
    }

    @Override
    public Optional<CartItem> findItemByPublicId(UUID publicId, long cartId) {
        return items.stream()
                .filter(item -> item.publicId().equals(publicId) && item.cartId() == cartId)
                .findFirst();
    }

    @Override
    public void insertItem(UUID publicId, CartItem item, Instant now) {
        items.add(withIds(nextItemId++, publicId, item, item.merchantVariantId()));
    }

    @Override
    public void updateItemOnMerge(long itemId, CartItem item, Instant now) {
        replace(itemId, existing -> withIds(itemId, existing.publicId(), item, existing.merchantVariantId()));
    }

    @Override
    public void updateItemVariant(long itemId, CartItem item, Instant now) {
        replace(itemId, existing -> withIds(itemId, existing.publicId(), item, item.merchantVariantId()));
    }

    @Override
    public int deleteItemById(long itemId) {
        return items.removeIf(item -> item.id() == itemId) ? 1 : 0;
    }

    @Override
    public int updateItemQuantity(UUID publicId, long cartId, int quantity, Instant now) {
        for (int index = 0; index < items.size(); index++) {
            CartItem item = items.get(index);
            if (item.publicId().equals(publicId) && item.cartId() == cartId) {
                items.set(index, new CartItem(
                        item.id(), item.publicId(), item.cartId(), item.merchantId(),
                        item.merchantProductId(), item.merchantVariantId(), item.productName(),
                        item.productUrl(), item.imageUrl(), item.selectedOptions(), quantity,
                        item.unitPriceAmount(), item.currency()));
                return 1;
            }
        }
        return 0;
    }

    @Override
    public int deleteItem(UUID publicId, long cartId) {
        return items.removeIf(item -> item.publicId().equals(publicId) && item.cartId() == cartId) ? 1 : 0;
    }

    @Override
    public List<CartItem> findItemsByCartId(long cartId) {
        return items.stream().filter(item -> item.cartId() == cartId).toList();
    }

    private void replace(long itemId, java.util.function.UnaryOperator<CartItem> mapper) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id() == itemId) {
                items.set(index, mapper.apply(items.get(index)));
                return;
            }
        }
    }

    private CartItem withIds(long id, UUID publicId, CartItem source, String merchantVariantId) {
        return new CartItem(
                id,
                publicId,
                source.cartId(),
                source.merchantId(),
                source.merchantProductId(),
                merchantVariantId,
                source.productName(),
                source.productUrl(),
                source.imageUrl(),
                source.selectedOptions(),
                source.quantity(),
                source.unitPriceAmount(),
                source.currency());
    }
}
