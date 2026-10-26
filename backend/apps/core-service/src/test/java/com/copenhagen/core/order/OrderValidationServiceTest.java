package com.copenhagen.core.order;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.copenhagen.core.address.Address;
import com.copenhagen.core.address.AddressRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
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
import com.copenhagen.core.checkout.MerchantCheckoutMessages.FulfillmentResult;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Item;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.LineItem;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Message;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Total;
import com.copenhagen.core.checkout.MerchantCheckoutRecord;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.payment.PayCheckoutHash;
import com.copenhagen.core.payment.PaymentApprovalClient;
import com.copenhagen.core.payment.PaymentApprovalMessages.ApprovalCreatedResponse;
import com.copenhagen.core.payment.PaymentApprovalMessages.ApprovalItem;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;
import com.copenhagen.core.product.MerchantsProperties;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderValidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER_ID = 7L;
    private static final long CART_ID = 100L;
    private static final long PURCHASE_REQUEST_ID = 500L;
    private static final long MERCHANT_REF_ID = 1L;
    private static final String MERCHANT_ID = "merchant-a";
    private static final UUID CART_PUBLIC_ID = UUID.randomUUID();
    private static final UUID ADDRESS_ID = UUID.randomUUID();
    private static final UUID VALIDATION_ID = UUID.randomUUID();
    private static final String JWS = "eyJhbGciOiJFUzI1NiJ9.eyJhIjoxfQ.sig";
    private static final String EXPIRES_AT = "2026-01-01T00:15:00Z";
    private static final long MERCHANT_CHECKOUT_ROW_ID = 900L;
    private static final UUID USER_PUBLIC_ID = UUID.randomUUID();
    private static final String APPROVAL_ID = "approval-1";

    private CartRepository cartRepository;
    private AddressRepository addressRepository;
    private PurchaseRequestRepository purchaseRequestRepository;
    private MerchantCheckoutRepository merchantCheckoutRepository;
    private MerchantCheckoutClient client;
    private UserRepository userRepository;
    private PaymentApprovalClient paymentApprovalClient;
    private OrderValidationService service;

    @BeforeEach
    void setUp() {
        cartRepository = mock(CartRepository.class);
        addressRepository = mock(AddressRepository.class);
        purchaseRequestRepository = mock(PurchaseRequestRepository.class);
        merchantCheckoutRepository = mock(MerchantCheckoutRepository.class);
        client = mock(MerchantCheckoutClient.class);
        userRepository = mock(UserRepository.class);
        paymentApprovalClient = mock(PaymentApprovalClient.class);

        PiiEncryptor piiEncryptor = mock(PiiEncryptor.class);
        when(piiEncryptor.decrypt(any())).thenReturn("복호화값");

        MerchantsProperties properties = new MerchantsProperties(List.of(
                new MerchantsProperties.MerchantConfig(
                        MERCHANT_ID, MERCHANT_REF_ID, "A몰", URI.create("http://merchant-a:8080"),
                        Duration.ofSeconds(1), Duration.ofSeconds(3))));

        service = new OrderValidationService(
                cartRepository,
                addressRepository,
                purchaseRequestRepository,
                merchantCheckoutRepository,
                new CartMerchants(properties),
                piiEncryptor,
                Map.of(MERCHANT_ID, client),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                userRepository,
                paymentApprovalClient,
                properties, new OrderValidationInputLoader(cartRepository, addressRepository),
                new PurchaseSuspensionProperties());

        when(merchantCheckoutRepository.insertAll(any(), any(), any()))
                .thenReturn(List.of(MERCHANT_CHECKOUT_ROW_ID));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(paymentApprovalClient.create(any(), any())).thenReturn(new ApprovalCreatedResponse(
                APPROVAL_ID, "PAYMENT_METHOD_REQUIRED", null, NOW.plusSeconds(300), "APPROVE_IN_APP", List.of()));
        when(cartRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(cart(1)));
        when(cartRepository.findItemsByCartId(CART_ID)).thenReturn(List.of(cartItem("p-1", "sku-1", 2, 5000L)));
        when(addressRepository.findActiveByPublicIdAndUserId(ADDRESS_ID, USER_ID))
                .thenReturn(Optional.of(address()));
        when(purchaseRequestRepository.insertCreatingCheckouts(
                any(), eq(USER_ID), eq(CART_ID), eq("APP"), eq("v1"), eq(NOW)))
                .thenReturn(new PurchaseRequest(
                        PURCHASE_REQUEST_ID, VALIDATION_ID, USER_ID, CART_ID, "CREATING_CHECKOUTS"));
    }

    @Test
    @DisplayName("검증에 성공하면 구매 요청과 merchant 주문서가 남고 validationId를 돌려준다")
    void validate_ready_persistsCheckoutAndReturnsValidationId() {
        when(client.create(any(), any())).thenReturn(readyCheckout(5000L, 2));

        OrderValidationResult result = service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID);

        assertThat(result.validationId()).isEqualTo(VALIDATION_ID);
        assertThat(result.checkouts()).hasSize(1);
        OrderValidationResult.MerchantCheckoutView view = result.checkouts().get(0);
        assertThat(view.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(view.merchantCheckoutId()).isEqualTo("checkout-1");
        assertThat(view.status()).isEqualTo("READY");
        assertThat(view.amount().subtotal()).isEqualTo(10000L);
        assertThat(view.amount().shippingFee()).isEqualTo(3000L);
        assertThat(view.amount().total()).isEqualTo(13000L);

        MerchantCheckout saved = captureRecord().checkout();
        assertThat(saved.purchaseRequestId()).isEqualTo(PURCHASE_REQUEST_ID);
        assertThat(saved.merchantId()).isEqualTo(MERCHANT_REF_ID);
        assertThat(saved.status()).isEqualTo("CREATING");
        assertThat(saved.checkoutDigest()).hasSize(64);
        assertThat(saved.checkoutExpiresAt()).isEqualTo(Instant.parse(EXPIRES_AT));
        verify(purchaseRequestRepository, never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("스냅샷은 merchant가 확정한 금액·통화·만료와 주문서 원문을 그대로 고정한다")
    void validate_ready_persistsSnapshot() {
        when(client.create(any(), any())).thenReturn(readyCheckout(5000L, 2));

        OrderValidationResult result = service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID);

        MerchantCheckoutRecord record = captureRecord();
        CheckoutSnapshot snapshot = record.snapshot();
        assertThat(result.checkouts().get(0).orderSnapshotId()).isEqualTo(snapshot.publicId());
        assertThat(snapshot.checkoutDigest()).isEqualTo(record.checkout().checkoutDigest());
        assertThat(snapshot.subtotalAmount()).isEqualTo(10000L);
        assertThat(snapshot.shippingFeeAmount()).isEqualTo(3000L);
        assertThat(snapshot.totalAmount()).isEqualTo(13000L);
        assertThat(snapshot.discountAmount()).isZero();
        assertThat(snapshot.taxAmount()).isZero();
        assertThat(snapshot.currency()).isEqualTo("KRW");
        assertThat(snapshot.expiresAt()).isEqualTo(Instant.parse(EXPIRES_AT));
        assertThat(snapshot.payload())
                .contains("\"id\":\"checkout-1\"")
                .contains("\"merchant_authorization\":\"" + JWS + "\"");
    }

    @Test
    @DisplayName("스냅샷의 배송지는 addresses의 암호문을 그대로 복사한다")
    void validate_ready_copiesAddressCiphertext() {
        when(client.create(any(), any())).thenReturn(readyCheckout(5000L, 2));

        service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID);

        CheckoutSnapshot snapshot = captureRecord().snapshot();
        Address address = address();
        assertThat(snapshot.recipientNameEncrypted()).isEqualTo(address.recipientNameEncrypted());
        // 연락처·배송 요청사항은 merchant에 보내지 못하지만 스냅샷에는 남는다.
        assertThat(snapshot.recipientPhoneEncrypted()).isEqualTo(address.recipientPhoneEncrypted());
        assertThat(snapshot.deliveryRequestEncrypted()).isEqualTo(address.deliveryRequestEncrypted());
        assertThat(snapshot.postalCodeEncrypted()).isEqualTo(address.postalCodeEncrypted());
        assertThat(snapshot.addressLine1Encrypted()).isEqualTo(address.addressLine1Encrypted());
        assertThat(snapshot.addressLine2Encrypted()).isEqualTo(address.addressLine2Encrypted());
    }

    @Test
    @DisplayName("항목 스냅샷은 상품 메타를 장바구니에서, 수량·단가를 merchant 응답에서 가져온다")
    void validate_ready_persistsSnapshotItems() {
        when(client.create(any(), any())).thenReturn(readyCheckout(5000L, 2));

        service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID);

        List<CheckoutSnapshotItem> items = captureRecord().items();
        assertThat(items).hasSize(1);
        CheckoutSnapshotItem item = items.get(0);
        assertThat(item.merchantProductId()).isEqualTo("p-1");
        assertThat(item.merchantVariantId()).isEqualTo("sku-1");
        assertThat(item.productUrl()).isEqualTo("http://merchant-a:8080/products/p-1");
        assertThat(item.selectedOptions()).contains("sku-1");
        assertThat(item.productName()).isEqualTo("상품");
        assertThat(item.quantity()).isEqualTo(2);
        assertThat(item.unitPriceAmount()).isEqualTo(5000L);
        assertThat(item.lineTotalAmount()).isEqualTo(10000L);
        assertThat(item.discountAmount()).isZero();
        assertThat(item.currency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("배송이 가능하면 배송 스냅샷 1행을 플레이스홀더로 남긴다")
    void validate_fulfillmentAvailable_persistsOneOption() {
        when(client.create(any(), any())).thenReturn(readyCheckout(5000L, 2));

        service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID);

        List<CheckoutFulfillmentOption> options = captureRecord().fulfillmentOptions();
        assertThat(options).hasSize(1);
        CheckoutFulfillmentOption option = options.get(0);
        assertThat(option.fulfillmentGroupKey()).isEqualTo("checkout-1");
        assertThat(option.shippingOptionId()).isEqualTo("default");
        assertThat(option.deliveryMethod()).isEqualTo("STANDARD");
        assertThat(option.shippingFeeAmount()).isEqualTo(3000L);
        assertThat(option.estimatedDeliveryAt()).isNull();
    }

    @Test
    @DisplayName("merchant가 배송 정보를 주지 않으면 배송 스냅샷을 남기지 않는다")
    void validate_noFulfillment_persistsNoOption() {
        when(client.create(any(), any())).thenReturn(new Checkout(
                "checkout-1", "ready_for_complete", "KRW",
                List.of(new LineItem("line-1", new Item("p-1", "상품", 5000L), 2)),
                null,
                List.of(new Total("subtotal", 10000L), new Total("total", 10000L)),
                null, EXPIRES_AT, JWS));

        service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID);

        assertThat(captureRecord().fulfillmentOptions()).isEmpty();
    }

    @Test
    @DisplayName("merchant 요청에 purchase_request_id와 장바구니 옵션·수량이 실린다")
    void validate_sendsPurchaseRequestIdAndLineItems() {
        when(client.create(any(), any())).thenReturn(readyCheckout(5000L, 2));

        service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID);

        ArgumentCaptor<CheckoutRequest> request = ArgumentCaptor.captor();
        ArgumentCaptor<String> idempotencyKey = ArgumentCaptor.captor();
        verify(client).create(request.capture(), idempotencyKey.capture());

        // 이 값이 없으면 merchant가 incomplete로 내린다(Merchant Authorization 필수 claim).
        assertThat(request.getValue().purchaseRequestId()).isEqualTo(VALIDATION_ID.toString());
        assertThat(request.getValue().lineItems()).hasSize(1);
        assertThat(request.getValue().lineItems().get(0).productId()).isEqualTo("p-1");
        assertThat(request.getValue().lineItems().get(0).optionValueIds()).containsExactly("sku-1");
        assertThat(request.getValue().lineItems().get(0).quantity()).isEqualTo(2);
        assertThat(request.getValue().fulfillment().type()).isEqualTo("shipping");
        assertThat(request.getValue().fulfillment().destination().recipient()).isEqualTo("복호화값");
        assertThat(idempotencyKey.getValue())
                .isEqualTo("checkout-create-" + VALIDATION_ID + "-" + MERCHANT_ID);
    }

    @Test
    @DisplayName("checkout이 확정되면 Pay에 승인 세션을 생성하고 AWAITING_APPROVAL로 반영한다(이슈 410)")
    void validate_ready_createsPaymentApprovalAndMarksAwaitingApproval() {
        when(client.create(any(), any())).thenReturn(readyCheckout(5000L, 2));

        service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID);

        ArgumentCaptor<CreateApprovalRequest> request = ArgumentCaptor.captor();
        ArgumentCaptor<String> idempotencyKey = ArgumentCaptor.captor();
        verify(paymentApprovalClient).create(request.capture(), idempotencyKey.capture());
        assertThat(request.getValue().purchaseRequestId()).isEqualTo(VALIDATION_ID.toString());
        assertThat(request.getValue().userId()).isEqualTo(USER_PUBLIC_ID.toString());
        assertThat(request.getValue().totalAmountMinor()).isEqualTo(13000L);
        assertThat(request.getValue().currency()).isEqualTo("KRW");
        assertThat(request.getValue().items()).hasSize(1);
        ApprovalItem item = request.getValue().items().get(0);
        assertThat(item.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(item.checkoutId()).isEqualTo("checkout-1");
        assertThat(item.checkoutJwt()).isEqualTo(JWS);
        // Pay는 hex가 아니라 "sha256:"+Base64URL 외부 표현만 받는다(CheckoutHashCodec.toInternalDigest) —
        // 내부 hex(merchant_checkouts.checkout_digest)를 그대로 보내면 400이 난다(도커 컴포즈 E2E로 발견).
        assertThat(item.checkoutHash())
                .isEqualTo(PayCheckoutHash.toExternal(CheckoutDigests.of(JWS)))
                .startsWith("sha256:")
                .hasSize(50);
        assertThat(item.amountMinor()).isEqualTo(13000L);
        assertThat(item.currency()).isEqualTo("KRW");
        assertThat(idempotencyKey.getValue())
                .isEqualTo(OrderValidationService.approvalIdempotencyKey(VALIDATION_ID));
        assertThat(UUID.fromString(idempotencyKey.getValue())).isNotNull();

        verify(merchantCheckoutRepository).markAwaitingApproval(
                MERCHANT_CHECKOUT_ROW_ID, APPROVAL_ID, NOW.plusSeconds(300), NOW);
    }

    @Test
    @DisplayName("드레인 구간에는 merchant를 부르기 전에 503 PURCHASE_SUSPENDED로 반려한다(이슈 486)")
    void validate_approvalSuspended_rejectsBeforeCreatingMerchantCheckout() {
        PurchaseSuspensionProperties suspended = new PurchaseSuspensionProperties();
        suspended.setApprovalSuspended(true);
        MerchantsProperties properties = new MerchantsProperties(List.of(
                new MerchantsProperties.MerchantConfig(
                        MERCHANT_ID, MERCHANT_REF_ID, "A몰", URI.create("http://merchant-a:8080"),
                        Duration.ofSeconds(1), Duration.ofSeconds(3))));
        OrderValidationService service = new OrderValidationService(
                cartRepository, addressRepository, purchaseRequestRepository, merchantCheckoutRepository,
                new CartMerchants(properties), mock(PiiEncryptor.class), Map.of(MERCHANT_ID, client),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), userRepository, paymentApprovalClient,
                properties, new OrderValidationInputLoader(cartRepository, addressRepository), suspended);

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.PURCHASE_SUSPENDED));

        // 만든 뒤 막으면 승인 없는 checkout이 남아 만료될 때까지 자리를 차지한다.
        verify(client, never()).create(any(), any());
        verify(merchantCheckoutRepository, never()).insertAll(any(), any(), any());
        verify(paymentApprovalClient, never()).create(any(), any());
    }

    @Test
    @DisplayName("Pay 승인 생성이 실패해도 이미 만든 checkout은 되돌리지 않고 CREATING으로 남긴다")
    void validate_paymentApprovalUnavailable_keepsCheckoutCreatingWithoutFailingValidation() {
        when(client.create(any(), any())).thenReturn(readyCheckout(5000L, 2));
        when(paymentApprovalClient.create(any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE));

        OrderValidationResult result = service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID);

        assertThat(result.checkouts()).hasSize(1);
        verify(merchantCheckoutRepository, never()).markAwaitingApproval(anyLong(), any(), any(), any());
        verify(purchaseRequestRepository, never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("재고 부족으로 반려되면 409 ORDER_STOCK_INSUFFICIENT와 항목 사유가 내려가고 주문서는 남지 않는다")
    void validate_stockInsufficient_returnsIssuesAndKeepsNoCheckout() {
        when(client.create(any(), any())).thenReturn(incompleteCheckout(
                new Message("CHECKOUT_STOCK_INSUFFICIENT", "error", "재고가 부족하다", "line_items[0]")));

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.ORDER_STOCK_INSUFFICIENT);
                    assertThat(exception.currentState()).isEqualTo("incomplete");
                    assertThat(exception.issues()).hasSize(1);
                    assertThat(exception.issues().get(0).code()).isEqualTo("CHECKOUT_STOCK_INSUFFICIENT");
                    assertThat(exception.issues().get(0).target()).isEqualTo("line_items[0]");
                    assertThat(exception.issues().get(0).merchantId()).isEqualTo(MERCHANT_ID);
                });

        verify(merchantCheckoutRepository, never()).insertAll(any(), any(), any());
        verify(purchaseRequestRepository).markFailed(PURCHASE_REQUEST_ID, NOW);
    }

    @Test
    @DisplayName("사유가 여러 건이면 우선순위가 가장 높은 코드를 대표로 쓰고 항목은 모두 싣는다")
    void validate_multipleIssues_usesPriorityErrorCode() {
        when(client.create(any(), any())).thenReturn(incompleteCheckout(
                new Message("ADDRESS_NOT_SERVICEABLE", "error", "배송할 수 없다", "fulfillment.destination"),
                new Message("CHECKOUT_STOCK_INSUFFICIENT", "error", "재고가 부족하다", "line_items[0]")));

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.ORDER_STOCK_INSUFFICIENT);
                    assertThat(exception.issues()).hasSize(2);
                });
    }

    @Test
    @DisplayName("담을 때와 단가가 다르면 409 ORDER_PRICE_CHANGED다")
    void validate_priceChanged_returns409() {
        when(client.create(any(), any())).thenReturn(readyCheckout(6000L, 2));

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.ORDER_PRICE_CHANGED);
                    assertThat(exception.issues().get(0).target()).isEqualTo("line_items[0].item.price");
                });
        verify(purchaseRequestRepository).markFailed(PURCHASE_REQUEST_ID, NOW);
    }

    @Test
    @DisplayName("수량이 어긋난 주문서는 ORDER_VALIDATION_FAILED다")
    void validate_quantityMismatch_returnsValidationFailed() {
        when(client.create(any(), any())).thenReturn(readyCheckout(5000L, 1));

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.ORDER_VALIDATION_FAILED));
    }

    @Test
    @DisplayName("ready인데 서명이 없으면 ORDER_VALIDATION_FAILED다")
    void validate_readyWithoutSignature_returnsValidationFailed() {
        when(client.create(any(), any())).thenReturn(new Checkout(
                "checkout-1", "ready_for_complete", "KRW",
                List.of(new LineItem("line-1", new Item("p-1", "상품", 5000L), 2)),
                new FulfillmentResult("shipping", "available", 3000L),
                List.of(new Total("subtotal", 10000L), new Total("total", 10000L)),
                null, EXPIRES_AT, null));

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.ORDER_VALIDATION_FAILED));
    }

    @Test
    @DisplayName("requires_escalation은 ORDER_MERCHANT_HANDOFF_REQUIRED다")
    void validate_requiresEscalation_returnsHandoffRequired() {
        Checkout checkout = new Checkout(
                "checkout-1", "requires_escalation", "KRW", List.of(), null, List.of(),
                List.of(new Message("MERCHANT_HANDOFF_REQUIRED", "error", "직접 문의", null)),
                null, null);
        when(client.create(any(), any())).thenReturn(checkout);

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(CoreErrorCode.ORDER_MERCHANT_HANDOFF_REQUIRED);
                    assertThat(exception.currentState()).isEqualTo("requires_escalation");
                });
    }

    @Test
    @DisplayName("merchant를 부르지 못하면 503이고 구매 요청은 FAILED로 닫힌다")
    void validate_merchantUnavailable_returns503AndMarksFailed() {
        when(client.create(any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE));

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE));
        verify(purchaseRequestRepository).markFailed(PURCHASE_REQUEST_ID, NOW);
    }

    @Test
    @DisplayName("장바구니 버전이 어긋나면 구매 요청을 만들지 않고 409다")
    void validate_staleCartVersion_returns409() {
        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 2, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.CART_VERSION_CONFLICT));
        verify(purchaseRequestRepository, never())
                .insertCreatingCheckouts(any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("다른 장바구니 식별자로 요청하면 409다")
    void validate_otherCartId_returns409() {
        assertThatThrownBy(() -> service.validate(USER_ID, UUID.randomUUID(), 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.CART_VERSION_CONFLICT));
    }

    @Test
    @DisplayName("빈 장바구니는 409 ORDER_CART_EMPTY다")
    void validate_emptyCart_returns409() {
        when(cartRepository.findItemsByCartId(CART_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.ORDER_CART_EMPTY));
        verify(purchaseRequestRepository, never())
                .insertCreatingCheckouts(any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("배송지가 없거나 남의 것이면 404 ADDRESS_NOT_FOUND다")
    void validate_addressNotFound_returns404() {
        when(addressRepository.findActiveByPublicIdAndUserId(ADDRESS_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(USER_ID, CART_PUBLIC_ID, 1, ADDRESS_ID))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(CoreErrorCode.ADDRESS_NOT_FOUND));
    }

    private MerchantCheckoutRecord captureRecord() {
        ArgumentCaptor<List<MerchantCheckoutRecord>> captor = ArgumentCaptor.captor();
        verify(merchantCheckoutRepository).insertAll(captor.capture(), any(), eq(NOW));
        return captor.getValue().get(0);
    }

    private static Cart cart(int version) {
        return new Cart(CART_ID, CART_PUBLIC_ID, USER_ID, version);
    }

    private static CartItem cartItem(String productId, String variantId, int quantity, long unitPrice) {
        return new CartItem(
                10L, UUID.randomUUID(), CART_ID, MERCHANT_REF_ID, productId, variantId,
                "상품", "http://merchant-a:8080/products/" + productId, null,
                "[{\"optionValueId\":\"" + variantId + "\"}]", quantity, unitPrice, "KRW");
    }

    private static User user() {
        return new User(
                USER_ID, USER_PUBLIC_ID, "홍길동", UserStatus.ACTIVE,
                EncryptedPii.of(new byte[] {1}), "hash", true,
                null, null, null, null, NOW, NOW, NOW);
    }

    /** 컬럼이 서로 뒤바뀌어 저장되는 것을 잡으려고 필드마다 다른 암호문을 준다. */
    private static Address address() {
        return new Address(
                20L, ADDRESS_ID, USER_ID,
                EncryptedPii.of(new byte[] {1}),
                EncryptedPii.of(new byte[] {2}),
                EncryptedPii.of(new byte[] {3}),
                EncryptedPii.of(new byte[] {4}),
                EncryptedPii.of(new byte[] {5}),
                EncryptedPii.of(new byte[] {6}),
                true, NOW, NOW);
    }

    private static Checkout readyCheckout(long unitPrice, int quantity) {
        return new Checkout(
                "checkout-1",
                "ready_for_complete",
                "KRW",
                List.of(new LineItem("line-1", new Item("p-1", "상품", unitPrice), quantity)),
                new FulfillmentResult("shipping", "available", 3000L),
                List.of(
                        new Total("subtotal", unitPrice * quantity),
                        new Total("fulfillment", 3000L),
                        new Total("total", unitPrice * quantity + 3000L)),
                null,
                EXPIRES_AT,
                JWS);
    }

    private static Checkout incompleteCheckout(Message... messages) {
        return new Checkout(
                "checkout-1", "incomplete", "KRW",
                List.of(new LineItem("line-1", new Item("p-1", "상품", 5000L), 2)),
                null,
                List.of(new Total("subtotal", 10000L), new Total("total", 10000L)),
                List.of(messages),
                null,
                null);
    }
}
