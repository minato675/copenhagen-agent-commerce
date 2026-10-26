package com.copenhagen.core.internalapi.cart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.cart.Cart;
import com.copenhagen.core.cart.CartItem;
import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.cart.CartRepository;
import com.copenhagen.core.cart.CartService;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;
import com.copenhagen.core.internalapi.idempotency.IdempotencyStore;
import com.copenhagen.core.product.CartProductSnapshot;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.product.ProductService;

/**
 * 내부 계약(설계서 §4.3~§4.6)에 맞춘 장바구니. 변경 자체는 앱 경로와 같은 {@link CartService}가 하고
 * (버전 낙관적 잠금·합산 정책 한 곳 유지), 여기서는 계약용 응답 조립·수량 상한·멱등 처리를 맡는다.
 *
 * <p>변경 응답도 전체 장바구니다. 변경과 조회가 같은 트랜잭션 안에서 끝나야 mcp-server가 받은
 * 장바구니가 방금 변경의 결과임이 보장된다.
 */
@Service
public class InternalCartService {

    /** mcp-server 입력 스키마의 수량 상한과 같다(설계서 D-G). 앱 경로에는 적용하지 않는다. */
    public static final int MAX_QUANTITY_PER_ITEM = 99;

    private static final String CURRENCY = "KRW";
    private static final String STATUS_ESTIMATED = "ESTIMATED";
    private static final TypeReference<List<CartProductSnapshot.SelectedOption>> SELECTED_OPTIONS_TYPE =
            new TypeReference<>() { };

    private final CartService cartService;
    private final CartRepository cartRepository;
    private final CartMerchants cartMerchants;
    private final ProductService productService;
    private final MerchantsProperties merchantsProperties;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    public InternalCartService(
            CartService cartService,
            CartRepository cartRepository,
            CartMerchants cartMerchants,
            ProductService productService,
            MerchantsProperties merchantsProperties,
            IdempotencyStore idempotencyStore,
            ObjectMapper objectMapper) {
        this.cartService = cartService;
        this.cartRepository = cartRepository;
        this.cartMerchants = cartMerchants;
        this.productService = productService;
        this.merchantsProperties = merchantsProperties;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
    }

    /** 조회지만 ACTIVE 장바구니가 없으면 만든다 — 내부 계약의 {@code cartId}는 비어 있을 수 없다(D-F). */
    @Transactional
    public InternalCartResponse get(InternalRequestContext context) {
        return view(cartService.getOrCreateActiveCart(context.userId()));
    }

    @Transactional
    public String add(
            InternalRequestContext context,
            String path,
            int cartVersion,
            String merchantId,
            String merchantProductId,
            String merchantVariantId,
            int quantity) {
        String canonicalRequest = String.join("&",
                "cartVersion=" + cartVersion,
                "merchantId=" + merchantId,
                "merchantProductId=" + merchantProductId,
                "merchantVariantId=" + merchantVariantId,
                "quantity=" + quantity);
        return idempotencyStore.execute(context, "POST", path, canonicalRequest, () -> {
            CartProductSnapshot snapshot =
                    capture(merchantId, merchantProductId, merchantVariantId);
            cartService.add(
                    context.userId(), cartVersion, merchantId, snapshot, quantity, MAX_QUANTITY_PER_ITEM);
            return view(requireCart(context.userId()));
        });
    }

    @Transactional
    public String update(
            InternalRequestContext context,
            String path,
            UUID cartItemId,
            int cartVersion,
            String merchantVariantId,
            int quantity) {
        String canonicalRequest = String.join("&",
                "cartVersion=" + cartVersion,
                "merchantVariantId=" + merchantVariantId,
                "quantity=" + quantity);
        return idempotencyStore.execute(context, "PATCH", path, canonicalRequest, () -> {
            Cart cart = requireCart(context.userId());
            CartItem item = cartRepository.findItemByPublicId(cartItemId, cart.id())
                    .orElseThrow(() -> new CoreException(CoreErrorCode.CART_ITEM_NOT_FOUND));
            if (item.merchantVariantId().equals(merchantVariantId)) {
                cartService.updateQuantity(context.userId(), cartItemId, cartVersion, quantity);
            } else {
                // 옵션 변경은 상품명·단가·옵션 표시값까지 새로 캡처해야 한다(설계서 §4.5).
                CartProductSnapshot snapshot = capture(
                        cartMerchants.merchantId(item.merchantId()),
                        item.merchantProductId(),
                        merchantVariantId);
                cartService.replaceVariant(
                        context.userId(), cartItemId, cartVersion, snapshot, quantity, MAX_QUANTITY_PER_ITEM);
            }
            return view(requireCart(context.userId()));
        });
    }

    @Transactional
    public String delete(InternalRequestContext context, String path, UUID cartItemId, int cartVersion) {
        String canonicalRequest = "cartVersion=" + cartVersion;
        return idempotencyStore.execute(context, "DELETE", path, canonicalRequest, () -> {
            cartService.remove(context.userId(), cartItemId, cartVersion);
            return view(requireCart(context.userId()));
        });
    }

    /**
     * merchant가 모르는 variant는 내부 계약에서 {@code VARIANT_NOT_FOUND}다. Core는 같은 상황에
     * {@code INVALID_REQUEST}("선택할 수 없는 옵션")를 쓰므로 여기서 번역한다 — variant를 지정해
     * 호출하므로 이 자리의 {@code INVALID_REQUEST}는 그 뜻 하나다.
     */
    private CartProductSnapshot capture(
            String merchantId, String merchantProductId, String merchantVariantId) {
        try {
            return productService.captureForCart(
                    merchantId, merchantProductId, List.of(merchantVariantId));
        } catch (CoreException exception) {
            if (exception.errorCode() == CoreErrorCode.INVALID_REQUEST) {
                throw new InternalApiException(InternalErrorCode.VARIANT_NOT_FOUND);
            }
            throw exception;
        }
    }

    private Cart requireCart(long userId) {
        return cartRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.CART_VERSION_CONFLICT));
    }

    private InternalCartResponse view(Cart cart) {
        List<CartItem> rows = cartRepository.findItemsByCartId(cart.id());
        List<InternalCartResponse.Item> items = new ArrayList<>(rows.size());
        Map<String, Long> subtotalByMerchant = new LinkedHashMap<>();
        long itemSubtotal = 0L;

        for (CartItem row : rows) {
            String merchantId = cartMerchants.merchantId(row.merchantId());
            long lineTotal = row.unitPriceAmount() * row.quantity();
            itemSubtotal += lineTotal;
            subtotalByMerchant.merge(merchantId, lineTotal, Long::sum);
            items.add(new InternalCartResponse.Item(
                    row.publicId().toString(),
                    merchantId,
                    row.merchantProductId(),
                    row.merchantVariantId(),
                    row.productName(),
                    row.imageUrl(),
                    row.quantity(),
                    row.unitPriceAmount(),
                    lineTotal,
                    row.currency(),
                    selectedOptions(row.selectedOptions())));
        }

        List<InternalCartResponse.MerchantSummary> merchantSummaries = subtotalByMerchant.entrySet().stream()
                .map(entry -> new InternalCartResponse.MerchantSummary(
                        entry.getKey(),
                        merchantName(entry.getKey()),
                        CURRENCY,
                        entry.getValue(),
                        null,
                        null,
                        null,
                        STATUS_ESTIMATED))
                .toList();

        return new InternalCartResponse(
                cart.publicId().toString(),
                cart.version(),
                List.copyOf(items),
                merchantSummaries,
                new InternalCartResponse.Summary(
                        CURRENCY, itemSubtotal, null, null, null, STATUS_ESTIMATED),
                // 장바구니는 DB만 읽는다 — merchant 호출이 없으니 실패할 판매처도 없다.
                List.of());
    }

    private List<InternalCartResponse.SelectedOption> selectedOptions(String selectedOptions) {
        if (selectedOptions == null || selectedOptions.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(selectedOptions, SELECTED_OPTIONS_TYPE).stream()
                .map(option -> new InternalCartResponse.SelectedOption(option.name(), option.label()))
                .toList();
    }

    /** 설정에서 빠진 merchant의 항목이 남아 있을 수 있어 이름을 못 찾으면 id를 그대로 쓴다. */
    private String merchantName(String merchantId) {
        List<MerchantsProperties.MerchantConfig> merchants = merchantsProperties.merchants();
        if (merchants == null) {
            return merchantId;
        }
        return merchants.stream()
                .filter(merchant -> merchant.id().equals(merchantId))
                .map(MerchantsProperties.MerchantConfig::displayName)
                .findFirst()
                .orElse(merchantId);
    }
}
