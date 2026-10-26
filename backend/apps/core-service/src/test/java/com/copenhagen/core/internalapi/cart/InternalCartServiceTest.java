package com.copenhagen.core.internalapi.cart;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.cart.CartService;
import com.copenhagen.core.cart.FakeCartRepository;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.internalapi.McpContractAssertions;
import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;
import com.copenhagen.core.internalapi.idempotency.FakeIdempotencyRepository;
import com.copenhagen.core.internalapi.idempotency.IdempotencyStore;
import com.copenhagen.core.product.CartProductSnapshot;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.product.ProductService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 설계서 §4.3~§4.6 — 내부 장바구니 응답이 mcp-server 계약을 통과하는지, 옵션 변경·합산·수량 상한·
 * 멱등 재전송이 설계대로 도는지 확인한다. Merchant Catalog 호출만 대역이고 장바구니 로직은 실제다.
 */
class InternalCartServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T05:00:00Z");
    private static final long USER_ID = 42L;
    private static final String ITEMS_PATH = "/internal/v1/carts/current/items";
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeCartRepository cartRepository = new FakeCartRepository();

    private ProductService productService;
    private InternalCartService internalCartService;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        MerchantsProperties merchantsProperties = new MerchantsProperties(List.of(
                new MerchantsProperties.MerchantConfig(
                        "merchant-a", 1L, "Copenhagen Demo Store", URI.create("http://merchant:8080"),
                        Duration.ofSeconds(1), Duration.ofSeconds(3))));
        CartMerchants cartMerchants = new CartMerchants(merchantsProperties);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CartService cartService = new CartService(
                cartRepository, productService, cartMerchants, objectMapper, clock);
        IdempotencyStore idempotencyStore =
                new IdempotencyStore(new FakeIdempotencyRepository(), objectMapper, clock);
        internalCartService = new InternalCartService(
                cartService, cartRepository, cartMerchants, productService, merchantsProperties,
                idempotencyStore, objectMapper);

        stubSnapshot("prod-1001", "sku-black", "갤럭시 S25", "블랙", 1200000L);
        stubSnapshot("prod-1001", "sku-white", "갤럭시 S25", "화이트", 1200000L);
    }

    @Test
    @DisplayName("장바구니가 없으면 만들어서 빈 장바구니를 돌려준다")
    void get_createsCartWhenMissing() {
        InternalCartResponse response = internalCartService.get(context(null));

        assertEquals(1, response.version());
        assertEquals(List.of(), response.items());
        assertEquals(0L, response.summary().itemSubtotalAmount());
        McpContractAssertions.assertCart(toMap(response));
    }

    @Test
    @DisplayName("담기 응답은 변경 후 전체 장바구니이고 금액은 수집된 값만 채운다")
    void add_returnsWholeCart() {
        String body = add(UUID.randomUUID(), 0, "sku-black", 2);

        Map<String, Object> cart = parse(body);
        McpContractAssertions.assertCart(cart);
        assertEquals(1, ((List<?>) cart.get("items")).size());
        assertEquals(2400000, itemField(cart, "lineTotalAmount"));
        assertEquals(2400000, ((Number) summary(cart).get("itemSubtotalAmount")).intValue());
        // 할인·배송비·최종금액은 수집되지 않았다. 0이 아니라 null이어야 한다.
        assertEquals(null, summary(cart).get("discountAmount"));
        assertEquals(null, summary(cart).get("shippingAmount"));
        assertEquals(null, summary(cart).get("totalAmount"));
        assertEquals("ESTIMATED", summary(cart).get("status"));
    }

    @Test
    @DisplayName("같은 키로 재전송하면 장바구니를 다시 바꾸지 않고 같은 응답을 돌려준다")
    void add_sameKeyTwice_isAppliedOnce() {
        UUID key = UUID.randomUUID();

        String first = add(key, 0, "sku-black", 2);
        String second = add(key, 0, "sku-black", 2);

        // 저장 계층이 JSONB라 실제 DB에서는 키 순서가 정규화된다. 값이 같은지로 확인한다.
        assertEquals(parse(first), parse(second));
        assertEquals(2, quantityOf(parse(second), 0));
    }

    @Test
    @DisplayName("같은 상품·옵션을 다시 담으면 수량을 합산한다")
    void add_sameVariant_mergesQuantity() {
        add(UUID.randomUUID(), 0, "sku-black", 2);
        String body = add(UUID.randomUUID(), 1, "sku-black", 3);

        Map<String, Object> cart = parse(body);
        assertEquals(1, ((List<?>) cart.get("items")).size());
        assertEquals(5, quantityOf(cart, 0));
    }

    @Test
    @DisplayName("합산 결과가 99를 넘으면 QUANTITY_LIMIT_EXCEEDED")
    void add_overLimit_isQuantityLimitExceeded() {
        add(UUID.randomUUID(), 0, "sku-black", 98);

        CoreException exception = assertThrows(CoreException.class,
                () -> add(UUID.randomUUID(), 1, "sku-black", 5));

        assertEquals(CoreErrorCode.CART_QUANTITY_LIMIT_EXCEEDED, exception.errorCode());
    }

    @Test
    @DisplayName("merchant가 모르는 variant로 담으면 VARIANT_NOT_FOUND")
    void add_unknownVariant_isVariantNotFound() {
        when(productService.captureForCart(eq("merchant-a"), eq("prod-1001"), eq(List.of("sku-none"))))
                .thenThrow(new CoreException(
                        CoreErrorCode.INVALID_REQUEST, "선택할 수 없는 옵션입니다.", null, "CHECK_REQUEST"));

        InternalApiException exception = assertThrows(InternalApiException.class,
                () -> add(UUID.randomUUID(), 0, "sku-none", 1));

        assertEquals(InternalErrorCode.VARIANT_NOT_FOUND, exception.errorCode());
    }

    @Test
    @DisplayName("같은 variant로 수량만 바꾸면 Catalog를 다시 부르지 않는다")
    void update_sameVariant_changesQuantityOnly() {
        add(UUID.randomUUID(), 0, "sku-black", 2);
        String cartItemId = cartItemId(parse(get()), 0);

        String body = internalCartService.update(
                context(UUID.randomUUID()), path(cartItemId), UUID.fromString(cartItemId),
                1, "sku-black", 5);

        Map<String, Object> cart = parse(body);
        McpContractAssertions.assertCart(cart);
        assertEquals(5, quantityOf(cart, 0));
        assertEquals("sku-black", itemText(cart, 0, "merchantVariantId"));
    }

    @Test
    @DisplayName("옵션을 바꾸면 새 스냅샷으로 variant·옵션 표시값이 갱신된다")
    void update_changesVariant() {
        add(UUID.randomUUID(), 0, "sku-black", 2);
        String cartItemId = cartItemId(parse(get()), 0);

        String body = internalCartService.update(
                context(UUID.randomUUID()), path(cartItemId), UUID.fromString(cartItemId),
                1, "sku-white", 2);

        Map<String, Object> cart = parse(body);
        McpContractAssertions.assertCart(cart);
        assertEquals("sku-white", itemText(cart, 0, "merchantVariantId"));
        assertEquals("화이트", selectedOptionValue(cart, 0));
    }

    @Test
    @DisplayName("바꾸려는 옵션이 이미 다른 줄에 있으면 두 줄을 합친다")
    void update_toExistingVariant_mergesLines() {
        add(UUID.randomUUID(), 0, "sku-black", 2);
        add(UUID.randomUUID(), 1, "sku-white", 3);
        String blackItemId = cartItemId(parse(get()), 0);

        String body = internalCartService.update(
                context(UUID.randomUUID()), path(blackItemId), UUID.fromString(blackItemId),
                2, "sku-white", 4);

        Map<String, Object> cart = parse(body);
        McpContractAssertions.assertCart(cart);
        assertEquals(1, ((List<?>) cart.get("items")).size());
        assertEquals(7, quantityOf(cart, 0));
        assertEquals("sku-white", itemText(cart, 0, "merchantVariantId"));
    }

    @Test
    @DisplayName("버전이 어긋난 변경은 CART_VERSION_CONFLICT")
    void update_staleVersion_isConflict() {
        add(UUID.randomUUID(), 0, "sku-black", 2);
        String cartItemId = cartItemId(parse(get()), 0);

        CoreException exception = assertThrows(CoreException.class, () -> internalCartService.update(
                context(UUID.randomUUID()), path(cartItemId), UUID.fromString(cartItemId),
                0, "sku-black", 5));

        assertEquals(CoreErrorCode.CART_VERSION_CONFLICT, exception.errorCode());
    }

    @Test
    @DisplayName("없는 항목을 바꾸려 하면 CART_ITEM_NOT_FOUND")
    void update_unknownItem_isNotFound() {
        add(UUID.randomUUID(), 0, "sku-black", 2);
        UUID unknown = UUID.randomUUID();

        CoreException exception = assertThrows(CoreException.class, () -> internalCartService.update(
                context(UUID.randomUUID()), path(unknown.toString()), unknown, 1, "sku-black", 5));

        assertEquals(CoreErrorCode.CART_ITEM_NOT_FOUND, exception.errorCode());
    }

    @Test
    @DisplayName("삭제 응답도 전체 장바구니다")
    void delete_returnsWholeCart() {
        add(UUID.randomUUID(), 0, "sku-black", 2);
        String cartItemId = cartItemId(parse(get()), 0);

        String body = internalCartService.delete(
                context(UUID.randomUUID()), path(cartItemId), UUID.fromString(cartItemId), 1);

        Map<String, Object> cart = parse(body);
        McpContractAssertions.assertCart(cart);
        assertEquals(0, ((List<?>) cart.get("items")).size());
        assertEquals(2, ((Number) cart.get("version")).intValue());
    }

    @Test
    @DisplayName("merchant별 소계는 판매처 이름과 함께 나간다")
    void view_buildsMerchantSummaries() {
        add(UUID.randomUUID(), 0, "sku-black", 2);

        Map<String, Object> cart = parse(get());
        List<?> summaries = (List<?>) cart.get("merchantSummaries");
        Map<?, ?> summary = (Map<?, ?>) summaries.get(0);
        assertEquals("merchant-a", summary.get("merchantId"));
        assertEquals("Copenhagen Demo Store", summary.get("merchantName"));
        assertEquals(2400000, ((Number) summary.get("itemSubtotalAmount")).intValue());
        assertEquals(null, summary.get("totalAmount"));
    }

    private String add(UUID key, int cartVersion, String variantId, int quantity) {
        return internalCartService.add(
                context(key), ITEMS_PATH, cartVersion, "merchant-a", "prod-1001", variantId, quantity);
    }

    private String get() {
        return objectMapper.writeValueAsString(internalCartService.get(context(null)));
    }

    private String path(String cartItemId) {
        return ITEMS_PATH + "/" + cartItemId;
    }

    private InternalRequestContext context(UUID idempotencyKey) {
        return new InternalRequestContext(USER_ID, UUID.randomUUID(), 1L, Set.of(), idempotencyKey);
    }

    private void stubSnapshot(
            String productId, String variantId, String name, String optionLabel, long price) {
        when(productService.captureForCart(eq("merchant-a"), eq(productId), eq(List.of(variantId))))
                .thenReturn(new CartProductSnapshot(
                        productId, variantId, name, "http://merchant:8080/products/" + productId, null,
                        price, "KRW",
                        List.of(new CartProductSnapshot.SelectedOption(variantId, "색상", optionLabel))));
    }

    private Map<String, Object> parse(String body) {
        return objectMapper.readValue(body, JSON_OBJECT);
    }

    private Map<String, Object> toMap(InternalCartResponse response) {
        return parse(objectMapper.writeValueAsString(response));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> cart) {
        return (Map<String, Object>) cart.get("summary");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> item(Map<String, Object> cart, int index) {
        return (Map<String, Object>) ((List<Object>) cart.get("items")).get(index);
    }

    private int itemField(Map<String, Object> cart, String field) {
        return ((Number) item(cart, 0).get(field)).intValue();
    }

    private int quantityOf(Map<String, Object> cart, int index) {
        return ((Number) item(cart, index).get("quantity")).intValue();
    }

    private String itemText(Map<String, Object> cart, int index, String field) {
        return (String) item(cart, index).get(field);
    }

    private String cartItemId(Map<String, Object> cart, int index) {
        return itemText(cart, index, "cartItemId");
    }

    @SuppressWarnings("unchecked")
    private String selectedOptionValue(Map<String, Object> cart, int index) {
        List<Object> options = (List<Object>) item(cart, index).get("selectedOptions");
        return (String) ((Map<String, Object>) options.get(0)).get("value");
    }
}
