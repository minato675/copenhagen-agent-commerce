package com.copenhagen.core.order;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.address.Address;
import com.copenhagen.core.address.AddressRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.cart.Cart;
import com.copenhagen.core.cart.CartItem;
import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.cart.CartRepository;
import com.copenhagen.core.checkout.CheckoutDigests;
import com.copenhagen.core.checkout.CheckoutFulfillmentOption;
import com.copenhagen.core.checkout.CheckoutSnapshot;
import com.copenhagen.core.checkout.CheckoutSnapshotItem;
import com.copenhagen.core.checkout.MerchantCheckout;
import com.copenhagen.core.checkout.MerchantCheckoutClient;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CheckoutRequest;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Destination;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Fulfillment;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.FulfillmentResult;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.LineItem;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.LineItemRequest;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Message;
import com.copenhagen.core.checkout.MerchantCheckoutRecord;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.ApiErrorIssue;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.payment.PaymentApprovalRequests;
import com.copenhagen.core.payment.PaymentApprovalClient;
import com.copenhagen.core.payment.PaymentApprovalMessages.ApprovalCreatedResponse;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;
import com.copenhagen.core.payment.PaymentApprovalRequests.ApprovalItemInput;
import com.copenhagen.core.product.MerchantsProperties;

/**
 * 주문 검증(순서 27·28, 이슈 S15P21A207-340·341). Core Cart를 merchant별 {@code line_items}로 나눠 Merchant
 * Checkout을 만들고, 돌아온 주문서가 사용자가 담은 것과 같은지 확인한 뒤 승인 대상 스냅샷으로 고정한다.
 * Merchant Cart는 만들지 않는다(ADR-0005).
 *
 * <p><b>merchant를 부르기 전에 {@code purchase_requests}를 먼저 만든다.</b> merchant가
 * {@code purchase_request_id} 없는 요청을 {@code incomplete}로 내리기 때문이다 — 그 값은 Merchant
 * Authorization의 필수 claim이고, 서명이 없으면 승인·완료로 갈 수 없다.
 *
 * <p>이 메서드에 {@code @Transactional}을 걸지 않는다. merchant HTTP 호출이 트랜잭션 안에서 커넥션을 잡고
 * 있으면 안 되고, 무엇보다 <b>실패 기록이 롤백되면 안 되기</b> 때문이다 — 반려된 구매 요청은
 * {@code FAILED}로 남아야 한다. 주문서와 스냅샷처럼 함께 남아야 하는 것만 Repository가 자체 트랜잭션을 연다.
 *
 * <p>가격·재고·배송비는 merchant가 원본이다(ADR-0004). Core는 그 값을 다시 계산하지 않고, 사용자가 담을 때
 * 본 값과 달라졌는지만 본다.
 */
@Service
public class OrderValidationService {

    private static final Logger log = LoggerFactory.getLogger(OrderValidationService.class);

    /** 적용한 서버 정책 버전. 정책 저장소가 생기기 전까지는 고정값이다. */
    static final String POLICY_VERSION = "v1";

    static final String IDEMPOTENCY_KEY_PREFIX = "checkout-create-";

    private static final String KRW = "KRW";
    private static final String FULFILLMENT_SHIPPING = "shipping";
    private static final String FULFILLMENT_AVAILABLE = "available";
    private static final String MERCHANT_STATUS_READY = "ready_for_complete";
    private static final String VIEW_STATUS_READY = "READY";
    private static final String TOTAL_SUBTOTAL = "subtotal";
    private static final String TOTAL_FULFILLMENT = "fulfillment";
    private static final String TOTAL_TOTAL = "total";

    /** merchant 계약에 할인·세금 개념이 없다. 컬럼은 있으나 채울 값이 없어 0으로 둔다. */
    private static final long NO_AMOUNT = 0L;

    /** 대표 오류코드 우선순위. 앞에 있을수록 사용자가 먼저 알아야 하는 사유다. */
    private static final List<CoreErrorCode> ERROR_CODE_PRIORITY = List.of(
            CoreErrorCode.ORDER_STOCK_INSUFFICIENT,
            CoreErrorCode.ORDER_PRICE_CHANGED,
            CoreErrorCode.ORDER_ADDRESS_NOT_SERVICEABLE,
            CoreErrorCode.ORDER_MERCHANT_HANDOFF_REQUIRED,
            CoreErrorCode.ORDER_VALIDATION_FAILED);

    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final MerchantCheckoutRepository merchantCheckoutRepository;
    private final CartMerchants cartMerchants;
    private final PiiEncryptor piiEncryptor;
    private final Map<String, MerchantCheckoutClient> clients;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final UserRepository userRepository;
    private final PaymentApprovalClient paymentApprovalClient;
    private final MerchantsProperties merchantsProperties;
    private final OrderValidationInputLoader inputLoader;
    private final PurchaseSuspensionProperties suspension;

    public OrderValidationService(
            CartRepository cartRepository,
            AddressRepository addressRepository,
            PurchaseRequestRepository purchaseRequestRepository,
            MerchantCheckoutRepository merchantCheckoutRepository,
            CartMerchants cartMerchants,
            PiiEncryptor piiEncryptor,
            Map<String, MerchantCheckoutClient> merchantCheckoutClients,
            ObjectMapper objectMapper,
            Clock coreClock,
            UserRepository userRepository,
            PaymentApprovalClient paymentApprovalClient,
            MerchantsProperties merchantsProperties, OrderValidationInputLoader inputLoader,
            PurchaseSuspensionProperties suspension) {
        this.cartRepository = cartRepository;
        this.addressRepository = addressRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.merchantCheckoutRepository = merchantCheckoutRepository;
        this.cartMerchants = cartMerchants;
        this.piiEncryptor = piiEncryptor;
        this.clients = merchantCheckoutClients;
        this.objectMapper = objectMapper;
        this.clock = coreClock;
        this.userRepository = userRepository;
        this.paymentApprovalClient = paymentApprovalClient;
        this.merchantsProperties = merchantsProperties;
        this.inputLoader = inputLoader;
        this.suspension = suspension;
    }

    public OrderValidationResult validate(long userId, UUID cartId, int cartVersion, UUID addressId) {
        Instant now = clock.instant();
        Cart cart = requireCart(userId, cartId, cartVersion);
        List<CartItem> items = cartRepository.findItemsByCartId(cart.id());
        if (items.isEmpty()) {
            throw new CoreException(CoreErrorCode.ORDER_CART_EMPTY);
        }
        if (items.size() > com.copenhagen.core.cart.CartService.MAX_CART_ITEMS) {
            throw new CoreException(CoreErrorCode.CART_ITEM_LIMIT_EXCEEDED);
        }
        Address address = requireAddress(userId, addressId);

        PurchaseRequest purchaseRequest = purchaseRequestRepository.insertCreatingCheckouts(
                UUID.randomUUID(), userId, cart.id(), PurchaseRequest.CHANNEL_APP, POLICY_VERSION, now);

        try {
            return createCheckouts(purchaseRequest, items, address, now);
        } catch (RuntimeException exception) {
            // 검증에 실패했거나 merchant를 부르지 못했다. 반려된 구매 요청은 다시 쓰지 않는다(ORDER-03).
            purchaseRequestRepository.markFailed(purchaseRequest.id(), clock.instant());
            throw exception;
        }
    }

    /** MCP 인계의 기존 ID와 connectionId를 보존하고 동일한 merchant 멱등키로 검증을 재개한다. */
    public OrderValidationResult validateExisting(
            PurchaseRequest purchase, UUID cartId, int cartVersion, UUID addressId) {
        OrderValidationInputLoader.Input input = inputLoader.load(purchase.userId(), cartId, cartVersion, addressId);
        return createCheckouts(purchase, input.items(), input.address(), clock.instant());
    }

    private OrderValidationResult createCheckouts(
            PurchaseRequest purchaseRequest,
            List<CartItem> items,
            Address address,
            Instant now) {
        // 드레인 구간에는 merchant checkout을 만들기 전에 막는다. 만든 뒤 막으면 승인 없는 checkout이
        // 남아 만료될 때까지 자리를 차지한다.
        if (suspension.isApprovalSuspended()) {
            throw new CoreException(CoreErrorCode.PURCHASE_SUSPENDED);
        }
        User user = userRepository.findById(purchaseRequest.userId())
                .orElseThrow(() -> new CoreException(CoreErrorCode.INTERNAL_ERROR));
        Destination destination = toDestination(address);
        List<DetectedIssue> issues = new ArrayList<>();
        List<MerchantCheckoutRecord> records = new ArrayList<>();
        List<OrderValidationResult.MerchantCheckoutView> views = new ArrayList<>();
        List<ApprovalItemInput> approvalInputs = new ArrayList<>();

        for (Map.Entry<Long, List<CartItem>> group : groupByMerchant(items).entrySet()) {
            String merchantId = cartMerchants.merchantId(group.getKey());
            MerchantCheckoutClient client = requireClient(merchantId);
            List<CartItem> merchantItems = group.getValue();

            Checkout checkout = client.create(
                    toRequest(purchaseRequest.publicId(), merchantItems, destination),
                    idempotencyKey(purchaseRequest.publicId(), merchantId));

            List<DetectedIssue> merchantIssues = verify(merchantId, merchantItems, checkout);
            if (!merchantIssues.isEmpty()) {
                issues.addAll(merchantIssues);
                continue;
            }

            UUID orderSnapshotId = UUID.randomUUID();
            String checkoutDigest = CheckoutDigests.of(checkout.merchantAuthorization());
            Instant expiresAt = toInstant(checkout.expiresAt());
            records.add(new MerchantCheckoutRecord(
                    MerchantCheckout.creating(
                            purchaseRequest.id(),
                            group.getKey(),
                            checkout.id(),
                            checkoutDigest,
                            now,
                            expiresAt),
                    toSnapshot(orderSnapshotId, checkoutDigest, checkout, address, expiresAt),
                    toSnapshotItems(merchantItems, checkout),
                    toFulfillmentOptions(checkout)));
            views.add(toView(merchantId, orderSnapshotId, checkout));
            approvalInputs.add(new ApprovalItemInput(
                    merchantId, merchantDisplayName(merchantId), checkoutDigest, checkout));
        }

        if (!issues.isEmpty()) {
            throw validationFailure(issues);
        }
        CreateApprovalRequest approvalRequest = toApprovalRequest(purchaseRequest, user, approvalInputs);
        List<Long> checkoutIds = merchantCheckoutRepository.insertAll(
                records, objectMapper.writeValueAsString(approvalRequest), now);
        createApproval(purchaseRequest, checkoutIds, approvalInputs, approvalRequest, views, now);
        return new OrderValidationResult(purchaseRequest.publicId(), List.copyOf(views));
    }

    /**
     * §5 — 확정된 checkout 전부를 담아 Pay에 승인 세션을 한 번 생성한다(이슈 410, S15P21A207-486).
     * Merchant 호출과 같은 이유로 {@code merchantCheckoutRepository.insertAll}이 끝난 뒤, 트랜잭션 밖에서
     * 부른다.
     *
     * <p>승인이 구매 요청당 1건이라 부분 성공이 없다. 실패하면 checkout 전부가 {@code CREATING}으로
     * 남고 {@code PaymentApprovalCreationWorker}가 같은 키·같은 본문으로 재시도한다.
     */
    private void createApproval(
            PurchaseRequest purchaseRequest, List<Long> checkoutIds, List<ApprovalItemInput> approvalInputs,
            CreateApprovalRequest request, List<OrderValidationResult.MerchantCheckoutView> views, Instant now) {
        try {
            ApprovalCreatedResponse response = paymentApprovalClient.create(
                    request, approvalIdempotencyKey(purchaseRequest.publicId()));
            for (long checkoutId : checkoutIds) {
                merchantCheckoutRepository.markAwaitingApproval(
                        checkoutId, response.approvalId(), response.expiresAt(), now);
            }
        } catch (CoreException exception) {
            for (int index = 0; index < views.size(); index++) {
                OrderValidationResult.MerchantCheckoutView view = views.get(index);
                views.set(index, new OrderValidationResult.MerchantCheckoutView(
                        view.merchantId(), view.merchantCheckoutId(), view.orderSnapshotId(),
                        "PENDING_APPROVAL_CREATION", view.expiresAt(), view.amount()));
            }
            log.warn("payment.approval.create.failed purchaseRequestId={}, items={}",
                    purchaseRequest.publicId(), approvalInputs.size(), exception);
        }
    }

    private CreateApprovalRequest toApprovalRequest(
            PurchaseRequest purchaseRequest, User user, List<ApprovalItemInput> inputs) {
        return PaymentApprovalRequests.create(
                purchaseRequest.publicId().toString(), user.publicId().toString(), inputs);
    }

    private String merchantDisplayName(String merchantId) {
        return merchantsProperties.merchants().stream()
                .filter(config -> merchantId.equals(config.id()))
                .findFirst()
                .map(MerchantsProperties.MerchantConfig::displayName)
                .orElse(merchantId);
    }

    /** 재시도가 같은 승인 세션으로 접히도록 결정적으로 만든다. */
    static String approvalIdempotencyKey(UUID purchaseRequestPublicId) {
        return PaymentApprovalRequests.idempotencyKey(purchaseRequestPublicId.toString());
    }

    /** 장바구니가 없거나 요청한 장바구니·버전과 어긋나면 재조회부터 시켜야 한다. */
    private Cart requireCart(long userId, UUID cartId, int cartVersion) {
        Cart cart = cartRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.CART_VERSION_CONFLICT));
        if (!cart.publicId().equals(cartId) || cart.version() != cartVersion) {
            throw new CoreException(CoreErrorCode.CART_VERSION_CONFLICT);
        }
        return cart;
    }

    private Address requireAddress(long userId, UUID addressId) {
        return addressRepository.findActiveByPublicIdAndUserId(addressId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.ADDRESS_NOT_FOUND));
    }

    /** UCP 배송지는 네 필드뿐이다. 연락처·배송 요청사항은 스냅샷에만 남는다. */
    private Destination toDestination(Address address) {
        return new Destination(
                piiEncryptor.decrypt(address.recipientNameEncrypted()),
                piiEncryptor.decrypt(address.postalCodeEncrypted()),
                piiEncryptor.decrypt(address.addressLine1Encrypted()),
                piiEncryptor.decrypt(address.addressLine2Encrypted()));
    }

    private MerchantCheckoutClient requireClient(String merchantId) {
        MerchantCheckoutClient client = clients.get(merchantId);
        if (client == null) {
            throw new CoreException(
                    CoreErrorCode.INVALID_REQUEST, "등록되지 않은 merchantId입니다.", null, "CHECK_REQUEST");
        }
        return client;
    }

    /** 담은 순서를 유지한다 — merchant 응답의 line item 순서가 요청 순서와 같아야 대조할 수 있다. */
    private static Map<Long, List<CartItem>> groupByMerchant(List<CartItem> items) {
        Map<Long, List<CartItem>> grouped = new LinkedHashMap<>();
        for (CartItem item : items) {
            grouped.computeIfAbsent(item.merchantId(), key -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    /**
     * {@code option_value_ids}는 담을 때 확정한 {@code merchant_variant_id} 하나다. merchant가 첫 값만
     * 보고 variant를 찾으므로 그 값이 곧 선택된 SKU다.
     */
    private static CheckoutRequest toRequest(
            UUID purchaseRequestPublicId, List<CartItem> items, Destination destination) {
        List<LineItemRequest> lineItems = items.stream()
                .map(item -> new LineItemRequest(
                        item.merchantProductId(), List.of(item.merchantVariantId()), item.quantity()))
                .toList();
        return new CheckoutRequest(
                lineItems,
                new Fulfillment(FULFILLMENT_SHIPPING, destination),
                purchaseRequestPublicId.toString());
    }

    /** 재시도가 같은 체크아웃으로 접히도록 결정적으로 만든다. merchant가 이 키에서 checkout id를 만든다. */
    static String idempotencyKey(UUID purchaseRequestPublicId, String merchantId) {
        return IDEMPOTENCY_KEY_PREFIX + purchaseRequestPublicId + "-" + merchantId;
    }

    /**
     * 승인 대상 스냅샷. 원본은 {@code payload}(merchant 주문서 전체)이고 금액 컬럼은 조회·표시용이다.
     *
     * <p>배송지는 {@code addresses}의 암호문을 그대로 옮긴다 — 복호화했다가 다시 암호화할 이유가 없다.
     */
    private CheckoutSnapshot toSnapshot(
            UUID publicId, String checkoutDigest, Checkout checkout, Address address, Instant expiresAt) {
        return new CheckoutSnapshot(
                0L,
                publicId,
                0L,
                checkoutDigest,
                writePayload(checkout),
                checkout.amountOf(TOTAL_SUBTOTAL),
                NO_AMOUNT,
                NO_AMOUNT,
                checkout.amountOf(TOTAL_FULFILLMENT),
                checkout.amountOf(TOTAL_TOTAL),
                checkout.currency(),
                address.recipientNameEncrypted(),
                address.recipientPhoneEncrypted(),
                address.postalCodeEncrypted(),
                address.addressLine1Encrypted(),
                address.addressLine2Encrypted(),
                address.deliveryRequestEncrypted(),
                expiresAt);
    }

    /**
     * 수량·단가는 merchant가 확정한 값, 상품 url·이미지 url·선택 옵션 상세는 장바구니에서 온다 —
     * merchant Checkout 응답에 그것들이 없다. 상품명은 "주문 당시 상품명"이라 merchant 응답을 쓴다.
     */
    private static List<CheckoutSnapshotItem> toSnapshotItems(List<CartItem> items, Checkout checkout) {
        List<LineItem> lines = checkout.lineItems();
        List<CheckoutSnapshotItem> snapshotItems = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            CartItem item = items.get(index);
            LineItem line = lines.get(index);
            snapshotItems.add(new CheckoutSnapshotItem(
                    item.merchantProductId(),
                    item.merchantVariantId(),
                    line.item().title(),
                    item.productUrl(),
                    item.imageUrl(),
                    item.selectedOptions(),
                    line.quantity(),
                    line.item().price(),
                    NO_AMOUNT,
                    line.item().price() * line.quantity(),
                    checkout.currency(), item.publicId()));
        }
        return List.copyOf(snapshotItems);
    }

    /**
     * merchant 주문서 하나가 곧 하나의 출고 그룹이다. 배송이 가능할 때만 한 행을 남기고, merchant 응답에
     * 없는 배송수단 정보는 플레이스홀더다.
     */
    private static List<CheckoutFulfillmentOption> toFulfillmentOptions(Checkout checkout) {
        FulfillmentResult fulfillment = checkout.fulfillment();
        if (fulfillment == null || !FULFILLMENT_AVAILABLE.equals(fulfillment.status())) {
            return List.of();
        }
        long fee = fulfillment.amount() == null
                ? checkout.amountOf(TOTAL_FULFILLMENT) : fulfillment.amount();
        return List.of(CheckoutFulfillmentOption.standard(checkout.id(), fee));
    }

    private String writePayload(Checkout checkout) {
        try {
            return objectMapper.writeValueAsString(checkout);
        } catch (JacksonException exception) {
            throw new CoreException(
                    CoreErrorCode.INTERNAL_ERROR,
                    CoreErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    CoreErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    private static List<DetectedIssue> verify(
            String merchantId, List<CartItem> items, Checkout checkout) {
        if (checkout == null) {
            return List.of(generic(merchantId, null, "판매자 주문서를 받지 못했습니다.", null));
        }
        if (!MERCHANT_STATUS_READY.equals(checkout.status())) {
            return fromMessages(merchantId, checkout);
        }

        List<DetectedIssue> issues = new ArrayList<>();
        if (checkout.merchantAuthorization() == null || checkout.merchantAuthorization().isBlank()) {
            issues.add(generic(merchantId, checkout.status(), "판매자 주문서에 서명이 없습니다.", null));
        }
        if (!KRW.equals(checkout.currency())) {
            issues.add(generic(merchantId, checkout.status(), "지원하지 않는 통화입니다.", null));
        }
        List<LineItem> lines = checkout.lineItems() == null ? List.of() : checkout.lineItems();
        if (lines.size() != items.size()) {
            issues.add(generic(merchantId, checkout.status(), "주문 항목이 장바구니와 다릅니다.", null));
            return List.copyOf(issues);
        }
        for (int index = 0; index < items.size(); index++) {
            issues.addAll(verifyLine(merchantId, checkout.status(), items.get(index), lines.get(index), index));
        }
        return List.copyOf(issues);
    }

    private static List<DetectedIssue> verifyLine(
            String merchantId, String currentState, CartItem item, LineItem line, int index) {
        String target = "line_items[" + index + "]";
        if (line.item() == null
                || !item.merchantProductId().equals(line.item().id())
                || line.quantity() != item.quantity()) {
            return List.of(new DetectedIssue(
                    CoreErrorCode.ORDER_VALIDATION_FAILED,
                    currentState,
                    new ApiErrorIssue(
                            merchantId,
                            CoreErrorCode.ORDER_VALIDATION_FAILED.code(),
                            target,
                            item.merchantProductId(),
                            "주문 항목이 장바구니와 다릅니다.")));
        }
        if (line.item().price() != item.unitPriceAmount()) {
            return List.of(new DetectedIssue(
                    CoreErrorCode.ORDER_PRICE_CHANGED,
                    currentState,
                    new ApiErrorIssue(
                            merchantId,
                            CoreErrorCode.ORDER_PRICE_CHANGED.code(),
                            target + ".item.price",
                            item.merchantProductId(),
                            "가격이 " + item.unitPriceAmount() + "에서 " + line.item().price() + "로 변경되었습니다.")));
        }
        return List.of();
    }

    /**
     * merchant가 완료 조건을 못 채웠다고 판정한 경우. {@code severity = error}만 사유로 삼는다 —
     * {@code warning}은 merchant가 진행 가능하다고 본 것이고, 가격 변동은 Core가 담은 값과 직접 대조한다.
     */
    private static List<DetectedIssue> fromMessages(String merchantId, Checkout checkout) {
        List<Message> messages = checkout.messages() == null ? List.of() : checkout.messages();
        List<DetectedIssue> issues = messages.stream()
                .filter(Message::blocking)
                .map(message -> new DetectedIssue(
                        mapErrorCode(message.code()),
                        checkout.status(),
                        new ApiErrorIssue(
                                merchantId, message.code(), message.target(), null, message.message())))
                .toList();
        if (issues.isEmpty()) {
            return List.of(generic(merchantId, checkout.status(), "주문을 진행할 수 없는 주문서입니다.", null));
        }
        return issues;
    }

    private static CoreErrorCode mapErrorCode(String merchantCode) {
        return switch (merchantCode == null ? "" : merchantCode) {
            case "CHECKOUT_STOCK_INSUFFICIENT" -> CoreErrorCode.ORDER_STOCK_INSUFFICIENT;
            case "ADDRESS_NOT_SERVICEABLE" -> CoreErrorCode.ORDER_ADDRESS_NOT_SERVICEABLE;
            case "MERCHANT_HANDOFF_REQUIRED" -> CoreErrorCode.ORDER_MERCHANT_HANDOFF_REQUIRED;
            default -> CoreErrorCode.ORDER_VALIDATION_FAILED;
        };
    }

    private static DetectedIssue generic(
            String merchantId, String currentState, String message, String target) {
        return new DetectedIssue(
                CoreErrorCode.ORDER_VALIDATION_FAILED,
                currentState,
                new ApiErrorIssue(
                        merchantId,
                        CoreErrorCode.ORDER_VALIDATION_FAILED.code(),
                        target,
                        null,
                        message));
    }

    private static CoreException validationFailure(List<DetectedIssue> issues) {
        CoreErrorCode representative = ERROR_CODE_PRIORITY.stream()
                .filter(code -> issues.stream().anyMatch(issue -> issue.errorCode() == code))
                .findFirst()
                .orElse(CoreErrorCode.ORDER_VALIDATION_FAILED);
        String currentState = issues.stream()
                .map(DetectedIssue::currentState)
                .filter(state -> state != null)
                .findFirst()
                .orElse(null);
        return new CoreException(
                representative,
                representative.defaultMessage(),
                currentState,
                representative.defaultNextAction(),
                issues.stream().map(DetectedIssue::issue).toList());
    }

    private static OrderValidationResult.MerchantCheckoutView toView(
            String merchantId, UUID orderSnapshotId, Checkout checkout) {
        return new OrderValidationResult.MerchantCheckoutView(
                merchantId,
                checkout.id(),
                orderSnapshotId,
                VIEW_STATUS_READY,
                checkout.expiresAt(),
                new OrderValidationResult.Amount(
                        checkout.amountOf(TOTAL_SUBTOTAL),
                        checkout.amountOf(TOTAL_FULFILLMENT),
                        checkout.amountOf(TOTAL_TOTAL),
                        checkout.currency()));
    }

    private static Instant toInstant(String isoDateTime) {
        return isoDateTime == null || isoDateTime.isBlank()
                ? null : OffsetDateTime.parse(isoDateTime).toInstant();
    }

    /** 항목 단위 사유와 그것이 매핑되는 Core 오류코드. 대표 코드는 이 중 우선순위가 가장 높은 것이다. */
    private record DetectedIssue(CoreErrorCode errorCode, String currentState, ApiErrorIssue issue) {
    }
}
