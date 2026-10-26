package com.copenhagen.core.internalapi.purchase;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.cart.Cart;
import com.copenhagen.core.cart.CartRepository;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.config.InternalApiProperties;
import com.copenhagen.core.internalapi.idempotency.IdempotencyStore;
import com.copenhagen.core.order.PurchaseRequest;
import com.copenhagen.core.order.PurchaseRequestRepository;

/**
 * MCP 구매 요청을 멱등하게 생성한다. 이후 PurchaseWorkflowWorker가 앱에 배송지 선택 알림을 보내고,
 * 사용자가 고른 배송지로 주문을 검증한 뒤 Pay의 결제 승인 요청과 푸시 알림으로 이어간다.
 *
 * <p>이 트랜잭션에서는 외부 호출을 하지 않는다. 배송지는 항상 같은 구매 요청을 앱에서 보완해 정한다.
 */
@Service
public class InternalPurchaseService {

    /** 앱 경로(순서 27)와 같은 정책 버전을 쓴다. */
    private static final String POLICY_VERSION = "v1";

    private final CartRepository cartRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final IdempotencyStore idempotencyStore;
    private final InternalApiProperties properties;
    private final Clock clock;

    public InternalPurchaseService(
            CartRepository cartRepository,
            PurchaseRequestRepository purchaseRequestRepository,
            IdempotencyStore idempotencyStore,
            InternalApiProperties properties,
            Clock coreClock) {
        this.cartRepository = cartRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.idempotencyStore = idempotencyStore;
        this.properties = properties;
        this.clock = coreClock;
    }

    @Transactional
    public String create(InternalRequestContext context, String path, UUID cartId, int cartVersion) {
        String canonicalRequest = "cartId=" + cartId + "&cartVersion=" + cartVersion;
        return idempotencyStore.execute(context, "POST", path, canonicalRequest, () -> {
            Cart cart = cartRepository.findActiveByUserId(context.userId())
                    .orElseThrow(() -> new CoreException(CoreErrorCode.CART_VERSION_CONFLICT));
            if (!cart.publicId().equals(cartId) || cart.version() != cartVersion) {
                throw new CoreException(CoreErrorCode.CART_VERSION_CONFLICT);
            }
            if (cartRepository.findItemsByCartId(cart.id()).isEmpty()) {
                throw new CoreException(CoreErrorCode.ORDER_CART_EMPTY);
            }

            Instant now = clock.instant();
            Instant expiresAt = now.plus(properties.handoffTtl());
            UUID publicId = UUID.randomUUID();
            purchaseRequestRepository.insertPendingAddress(
                    publicId,
                    context.userId(),
                    cart.id(),
                    context.connectionId(),
                    POLICY_VERSION,
                    cartVersion,
                    expiresAt,
                    now);

            return new InternalPurchaseResponse(
                    publicId.toString(),
                    PurchaseRequest.STATUS_PENDING_ADDRESS,
                    cartId.toString(),
                    cartVersion,
                    expiresAt.toString(),
                    InternalPurchaseResponse.NEXT_ACTION_SELECT_ADDRESS);
        });
    }
}
