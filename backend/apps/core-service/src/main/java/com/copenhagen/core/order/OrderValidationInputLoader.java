package com.copenhagen.core.order;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.address.Address;
import com.copenhagen.core.address.AddressRepository;
import com.copenhagen.core.cart.Cart;
import com.copenhagen.core.cart.CartItem;
import com.copenhagen.core.cart.CartRepository;
import com.copenhagen.core.cart.CartService;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/** 버전 확인·품목·배송지를 하나의 DB 스냅샷에서 읽고 외부 HTTP 호출 전에 트랜잭션을 끝낸다. */
@Service
public class OrderValidationInputLoader {
    private final CartRepository carts;
    private final AddressRepository addresses;

    public OrderValidationInputLoader(CartRepository carts, AddressRepository addresses) {
        this.carts = carts;
        this.addresses = addresses;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Input load(long userId, UUID cartId, int version, UUID addressId) {
        Cart cart = carts.findActiveByUserId(userId)
                .filter(value -> value.publicId().equals(cartId) && value.version() == version)
                .orElseThrow(() -> new CoreException(CoreErrorCode.CART_VERSION_CONFLICT));
        List<CartItem> items = carts.findItemsByCartId(cart.id());
        if (items.isEmpty()) {
            throw new CoreException(CoreErrorCode.ORDER_CART_EMPTY);
        }
        if (items.size() > CartService.MAX_CART_ITEMS) {
            throw new CoreException(CoreErrorCode.CART_ITEM_LIMIT_EXCEEDED);
        }
        Address address = addresses.findActiveByPublicIdAndUserId(addressId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.ADDRESS_NOT_FOUND));
        return new Input(cart, List.copyOf(items), address);
    }

    public record Input(Cart cart, List<CartItem> items, Address address) {
    }
}
