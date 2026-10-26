package com.copenhagen.core.internalapi.purchase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.cart.Cart;
import com.copenhagen.core.cart.CartItem;
import com.copenhagen.core.cart.FakeCartRepository;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.config.InternalApiProperties;
import com.copenhagen.core.internalapi.idempotency.FakeIdempotencyRepository;
import com.copenhagen.core.internalapi.idempotency.IdempotencyStore;
import com.copenhagen.core.order.FakePurchaseRequestRepository;
import com.copenhagen.core.order.PurchaseRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 구매 요청을 저장하면 workflow가 기본 배송지 검증과 Pay 승인 알림을 이어간다. */
class InternalPurchaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T05:00:00Z");
    private static final long USER_ID = 42L;
    private static final long CONNECTION_ID = 7L;
    private static final String PATH = "/internal/v1/purchase-requests";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeCartRepository cartRepository = new FakeCartRepository();
    private final FakePurchaseRequestRepository purchaseRequestRepository = new FakePurchaseRequestRepository();

    private Cart cart;
    private InternalPurchaseService purchaseService;

    @BeforeEach
    void setUp() {
        cart = cartRepository.insertActiveCart(UUID.randomUUID(), USER_ID, NOW);
        purchaseService = service();
    }

    @Test
    @DisplayName("App Link 설정 없이 후속 처리할 구매 요청을 저장한다")
    void create_insertsPendingAddressRow() {
        addItem();

        InternalPurchaseResponse response = parse(purchaseService.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version()));

        assertEquals(PurchaseRequest.STATUS_PENDING_ADDRESS, response.status());
        assertEquals(InternalPurchaseResponse.NEXT_ACTION_SELECT_ADDRESS, response.nextAction());
        assertEquals(cart.publicId().toString(), response.cartId());
        assertEquals(cart.version(), response.cartVersion());
        assertEquals(NOW.plus(Duration.ofMinutes(30)).toString(), response.expiresAt());

        assertEquals(1, purchaseRequestRepository.rows.size());
        FakePurchaseRequestRepository.Row row = purchaseRequestRepository.rows.get(0);
        assertEquals(PurchaseRequest.STATUS_PENDING_ADDRESS, row.status());
        assertEquals(PurchaseRequest.CHANNEL_MCP, row.requestChannel());
        assertEquals(CONNECTION_ID, row.agentConnectionId());
        assertEquals(cart.version(), row.cartVersion());
        assertEquals(cart.id(), row.cartId());
    }

    @Test
    @DisplayName("만료는 mcp-server가 거부하는 31분보다 이르다")
    void create_expiresBeforeMcpLimit() {
        addItem();

        InternalPurchaseResponse response = parse(purchaseService.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version()));

        assertTrue(Instant.parse(response.expiresAt()).isBefore(NOW.plusSeconds(1860)));
    }

    @Test
    @DisplayName("같은 키 재전송은 새 구매 요청을 만들지 않는다")
    void create_sameKeyTwice_insertsOnce() {
        addItem();
        UUID key = UUID.randomUUID();

        String first = purchaseService.create(context(key), PATH, cart.publicId(), cart.version());
        String second = purchaseService.create(context(key), PATH, cart.publicId(), cart.version());

        assertEquals(parse(first), parse(second));
        assertEquals(1, purchaseRequestRepository.rows.size());
    }

    @Test
    @DisplayName("빈 장바구니는 CART_EMPTY")
    void create_emptyCart_isCartEmpty() {
        CoreException exception = assertThrows(CoreException.class, () -> purchaseService.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version()));

        assertEquals(CoreErrorCode.ORDER_CART_EMPTY, exception.errorCode());
        assertEquals(0, purchaseRequestRepository.rows.size());
    }

    @Test
    @DisplayName("cartId가 다르면 CART_VERSION_CONFLICT")
    void create_otherCartId_isConflict() {
        addItem();

        CoreException exception = assertThrows(CoreException.class, () -> purchaseService.create(
                context(UUID.randomUUID()), PATH, UUID.randomUUID(), cart.version()));

        assertEquals(CoreErrorCode.CART_VERSION_CONFLICT, exception.errorCode());
    }

    @Test
    @DisplayName("버전이 다르면 CART_VERSION_CONFLICT")
    void create_staleVersion_isConflict() {
        addItem();

        CoreException exception = assertThrows(CoreException.class, () -> purchaseService.create(
                context(UUID.randomUUID()), PATH, cart.publicId(), cart.version() + 1));

        assertEquals(CoreErrorCode.CART_VERSION_CONFLICT, exception.errorCode());
    }

    @Test
    @DisplayName("구매 응답은 앱 이동 링크 없이 생성 스냅샷을 반환한다")
    void create_returnsReceiptWithoutAppLink() {
        addItem();
        String response = purchaseService.create(context(UUID.randomUUID()), PATH, cart.publicId(), cart.version());
        assertFalse(objectMapper.readTree(response).has("appLink"));
        assertEquals(1, purchaseRequestRepository.rows.size());
    }

    private InternalPurchaseService service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new InternalPurchaseService(
                cartRepository,
                purchaseRequestRepository,
                new IdempotencyStore(new FakeIdempotencyRepository(), objectMapper, clock),
                new InternalApiProperties(Duration.ofMinutes(30), 10),
                clock);
    }

    private void addItem() {
        cartRepository.insertItem(UUID.randomUUID(), new CartItem(
                0L, null, cart.id(), 1L, "prod-1001", "sku-black", "갤럭시 S25",
                "http://merchant:8080/products/prod-1001", null, "[]", 1, 1200000L, "KRW"), NOW);
    }

    private InternalRequestContext context(UUID idempotencyKey) {
        return new InternalRequestContext(
                USER_ID, UUID.randomUUID(), CONNECTION_ID, Set.of(), idempotencyKey);
    }

    private InternalPurchaseResponse parse(String body) {
        return objectMapper.readValue(body, InternalPurchaseResponse.class);
    }
}
