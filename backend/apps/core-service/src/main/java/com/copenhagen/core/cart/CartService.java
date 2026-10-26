package com.copenhagen.core.cart;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.product.CartProductSnapshot;
import com.copenhagen.core.product.ProductService;

/**
 * 통합 장바구니(순서 25, 이슈 S15P21A207-339). 기준은 API 명세서 v5의 CART 4건이다.
 *
 * <p>모든 변경은 {@code cartVersion} 낙관적 잠금 위에서 돈다. 요청이 들고 온 버전으로 조건부 UPDATE를
 * 먼저 걸어 버전을 올리고, 영향 행이 0이면 그 자리에서 {@code CART_VERSION_CONFLICT}(409,
 * {@code nextAction: REFETCH_CART})를 던진다. 버전 UPDATE를 항목 변경보다 먼저 하는 이유는, 같은
 * 장바구니를 동시에 고치는 요청들을 {@code carts} 행 잠금으로 직렬화하기 위해서다(CART-05의 "동시 수정
 * 충돌 시 최신 상태를 임의로 덮어쓰지 않는다").
 */
@Service
public class CartService {

    public static final int MAX_CART_ITEMS = 100;

    static final String DEFAULT_CURRENCY = "KRW";

    /** 장바구니가 아직 없는 상태의 논리적 버전. 첫 담기 요청은 이 값을 보내야 한다. */
    private static final int NO_CART_VERSION = 0;

    /** 앱 경로는 줄당 수량 상한이 없다. 상한은 내부 API(순서 34)에만 적용한다(설계서 D-G). */
    public static final int NO_QUANTITY_LIMIT = 0;

    private static final TypeReference<List<CartProductSnapshot.SelectedOption>> SELECTED_OPTIONS_TYPE =
            new TypeReference<>() { };

    private final CartRepository cartRepository;
    private final ProductService productService;
    private final CartMerchants cartMerchants;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CartService(
            CartRepository cartRepository,
            ProductService productService,
            CartMerchants cartMerchants,
            ObjectMapper objectMapper,
            Clock coreClock) {
        this.cartRepository = cartRepository;
        this.productService = productService;
        this.cartMerchants = cartMerchants;
        this.objectMapper = objectMapper;
        this.clock = coreClock;
    }

    /** 조회는 버전을 올리지 않는다. 장바구니가 없으면 빈 응답이다. */
    @Transactional(readOnly = true)
    public CartView get(long userId) {
        Optional<Cart> cart = cartRepository.findActiveByUserId(userId);
        if (cart.isEmpty()) {
            return CartView.empty();
        }
        return toView(cart.get(), cartRepository.findItemsByCartId(cart.get().id()));
    }

    /**
     * 내부 API(순서 34)는 {@code cartId}가 항상 있어야 해서 ACTIVE 장바구니가 없으면 만들어서 돌려준다
     * (설계서 D-F). 앱 경로의 {@link #get}은 지금처럼 빈 응답을 그대로 쓴다.
     */
    @Transactional
    public Cart getOrCreateActiveCart(long userId) {
        return cartRepository.findActiveByUserId(userId)
                .orElseGet(() -> createCart(userId, clock.instant()));
    }

    @Transactional
    public CartAddResult add(
            long userId,
            int cartVersion,
            String merchantId,
            String productId,
            List<String> optionValueIds,
            int quantity) {
        return add(
                userId,
                cartVersion,
                merchantId,
                productService.captureForCart(merchantId, productId, optionValueIds),
                quantity,
                NO_QUANTITY_LIMIT);
    }

    /**
     * 스냅샷을 미리 뜬 담기. 내부 API는 Catalog 오류를 자기 오류 코드로 바꿔야 해서(설계서 §8)
     * 캡처를 직접 하고 결과만 넘긴다. 줄당 수량 상한도 내부 경로에서만 쓴다.
     */
    @Transactional
    public CartAddResult add(
            long userId,
            int cartVersion,
            String merchantId,
            CartProductSnapshot snapshot,
            int quantity,
            int maxQuantityPerItem) {
        Instant now = clock.instant();
        long merchantRefId = cartMerchants.requireRefId(merchantId);

        Cart cart = cartRepository.findActiveByUserId(userId).orElse(null);
        int newVersion;
        if (cart == null) {
            if (cartVersion != NO_CART_VERSION) {
                throw new CoreException(CoreErrorCode.CART_VERSION_CONFLICT);
            }
            cart = createCart(userId, now);
            newVersion = cart.version();
        } else {
            newVersion = bumpVersion(cart, cartVersion, now);
        }

        String selectedOptions = writeSelectedOptions(snapshot.selectedOptions());
        Optional<CartItem> existing = cartRepository.findItemByProductVariant(
                cart.id(), merchantRefId, snapshot.merchantProductId(), snapshot.merchantVariantId());

        if (existing.isPresent()) {
            // 같은 상품·같은 옵션은 한 줄만 존재할 수 있어(uq_cart_items_cart_product_variant) 수량을 합산한다.
            // CART-01의 "합산 또는 갱신 정책" 중 합산이고, 응답이 cartItemId 하나를 요구하는 것과도 맞는다.
            CartItem item = existing.get();
            int mergedQuantity = item.quantity() + quantity;
            requireWithinQuantityLimit(mergedQuantity, maxQuantityPerItem);
            cartRepository.updateItemOnMerge(
                    item.id(),
                    toRow(cart.id(), merchantRefId, snapshot, selectedOptions, mergedQuantity),
                    now);
            return new CartAddResult(item.publicId(), newVersion);
        }

        if (cartRepository.findItemsByCartId(cart.id()).size() >= MAX_CART_ITEMS) {
            throw new CoreException(CoreErrorCode.CART_ITEM_LIMIT_EXCEEDED);
        }
        requireWithinQuantityLimit(quantity, maxQuantityPerItem);
        UUID cartItemId = UUID.randomUUID();
        cartRepository.insertItem(
                cartItemId, toRow(cart.id(), merchantRefId, snapshot, selectedOptions, quantity), now);
        return new CartAddResult(cartItemId, newVersion);
    }

    /** 수량 변경은 Catalog를 호출하지 않는다 — 담을 때 캡처한 단가를 그대로 둔다. */
    @Transactional
    public int updateQuantity(long userId, UUID cartItemId, int cartVersion, int quantity) {
        Instant now = clock.instant();
        Cart cart = requireCart(userId);
        int newVersion = bumpVersion(cart, cartVersion, now);
        if (cartRepository.updateItemQuantity(cartItemId, cart.id(), quantity, now) == 0) {
            throw new CoreException(CoreErrorCode.CART_ITEM_NOT_FOUND);
        }
        return newVersion;
    }

    /**
     * 옵션(variant) 변경(순서 34, 설계서 §4.5). 스냅샷은 호출자가 새 variant로 다시 떠서 넘긴다 —
     * 상품명·단가·옵션 표시값이 옵션에 따라 달라질 수 있기 때문이다.
     *
     * <p>바꾸려는 variant가 이미 다른 줄에 있으면({@code uq_cart_items_cart_product_variant})
     * 두 줄을 합친다. 남는 줄의 수량은 요청 수량 + 상대 줄 수량이고 편집한 줄은 지운다.
     * 담기의 합산 정책(CART-01)과 같은 규칙이다.
     */
    @Transactional
    public int replaceVariant(
            long userId,
            UUID cartItemId,
            int cartVersion,
            CartProductSnapshot snapshot,
            int quantity,
            int maxQuantityPerItem) {
        Instant now = clock.instant();
        Cart cart = requireCart(userId);
        int newVersion = bumpVersion(cart, cartVersion, now);

        CartItem item = cartRepository.findItemByPublicId(cartItemId, cart.id())
                .orElseThrow(() -> new CoreException(CoreErrorCode.CART_ITEM_NOT_FOUND));
        String selectedOptions = writeSelectedOptions(snapshot.selectedOptions());
        Optional<CartItem> target = cartRepository.findItemByProductVariant(
                cart.id(), item.merchantId(), snapshot.merchantProductId(), snapshot.merchantVariantId());

        if (target.isPresent() && target.get().id() != item.id()) {
            int mergedQuantity = quantity + target.get().quantity();
            requireWithinQuantityLimit(mergedQuantity, maxQuantityPerItem);
            cartRepository.updateItemOnMerge(
                    target.get().id(),
                    toRow(cart.id(), item.merchantId(), snapshot, selectedOptions, mergedQuantity),
                    now);
            cartRepository.deleteItemById(item.id());
            return newVersion;
        }

        requireWithinQuantityLimit(quantity, maxQuantityPerItem);
        cartRepository.updateItemVariant(
                item.id(), toRow(cart.id(), item.merchantId(), snapshot, selectedOptions, quantity), now);
        return newVersion;
    }

    /** {@code cart_items}에는 {@code deleted_at}이 없어 hard delete다. */
    @Transactional
    public int remove(long userId, UUID cartItemId, int cartVersion) {
        Instant now = clock.instant();
        Cart cart = requireCart(userId);
        int newVersion = bumpVersion(cart, cartVersion, now);
        if (cartRepository.deleteItem(cartItemId, cart.id()) == 0) {
            throw new CoreException(CoreErrorCode.CART_ITEM_NOT_FOUND);
        }
        return newVersion;
    }

    private Cart createCart(long userId, Instant now) {
        try {
            return cartRepository.insertActiveCart(UUID.randomUUID(), userId, now);
        } catch (DuplicateKeyException exception) {
            // uq_carts_user_active 위반 = 다른 요청이 방금 장바구니를 만들었다. 재조회 후 재시도가 맞다.
            throw new CoreException(CoreErrorCode.CART_VERSION_CONFLICT);
        }
    }

    /** 장바구니가 없는데 변경을 요청하면, 요청 버전이 무엇이든 재조회를 시켜야 한다. */
    private Cart requireCart(long userId) {
        return cartRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.CART_VERSION_CONFLICT));
    }

    private void requireWithinQuantityLimit(int quantity, int maxQuantityPerItem) {
        if (maxQuantityPerItem > NO_QUANTITY_LIMIT && quantity > maxQuantityPerItem) {
            throw new CoreException(CoreErrorCode.CART_QUANTITY_LIMIT_EXCEEDED);
        }
    }

    private int bumpVersion(Cart cart, int expectedVersion, Instant now) {
        if (!cartRepository.bumpVersion(cart.id(), expectedVersion, now)) {
            throw new CoreException(CoreErrorCode.CART_VERSION_CONFLICT);
        }
        return expectedVersion + 1;
    }

    private CartItem toRow(
            long cartId,
            long merchantRefId,
            CartProductSnapshot snapshot,
            String selectedOptions,
            int quantity) {
        return new CartItem(
                0L,
                null,
                cartId,
                merchantRefId,
                snapshot.merchantProductId(),
                snapshot.merchantVariantId(),
                snapshot.productName(),
                snapshot.productUrl(),
                snapshot.imageUrl(),
                selectedOptions,
                quantity,
                snapshot.unitPriceAmount(),
                snapshot.currency());
    }

    private CartView toView(Cart cart, List<CartItem> items) {
        List<CartView.CartItemView> views = new ArrayList<>(items.size());
        long itemsTotal = 0L;
        String currency = DEFAULT_CURRENCY;
        for (CartItem item : items) {
            long lineTotal = item.unitPriceAmount() * item.quantity();
            itemsTotal += lineTotal;
            currency = item.currency();
            views.add(new CartView.CartItemView(
                    item.publicId(),
                    cartMerchants.merchantId(item.merchantId()),
                    item.merchantProductId(),
                    readOptionValueIds(item.selectedOptions()),
                    item.productName(),
                    item.productUrl(),
                    item.imageUrl(),
                    item.quantity(),
                    new CartView.Price(item.unitPriceAmount(), item.currency()),
                    lineTotal));
        }
        CartView.CartSummary summary = new CartView.CartSummary(itemsTotal, 0L, 0L, itemsTotal, currency);
        return new CartView(cart.publicId(), cart.version(), List.copyOf(views), summary);
    }

    private String writeSelectedOptions(List<CartProductSnapshot.SelectedOption> selectedOptions) {
        try {
            return objectMapper.writeValueAsString(selectedOptions);
        } catch (JacksonException exception) {
            throw new CoreException(
                    CoreErrorCode.INTERNAL_ERROR,
                    CoreErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    CoreErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    private List<String> readOptionValueIds(String selectedOptions) {
        if (selectedOptions == null || selectedOptions.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(selectedOptions, SELECTED_OPTIONS_TYPE).stream()
                    .map(CartProductSnapshot.SelectedOption::optionValueId)
                    .toList();
        } catch (JacksonException exception) {
            throw new CoreException(
                    CoreErrorCode.INTERNAL_ERROR,
                    CoreErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    CoreErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }
}
