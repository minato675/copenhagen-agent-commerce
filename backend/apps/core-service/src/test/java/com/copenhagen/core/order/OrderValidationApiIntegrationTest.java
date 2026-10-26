package com.copenhagen.core.order;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.auth.signup.SignupService;
import com.copenhagen.core.checkout.MerchantCheckoutClient;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CompleteResult;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.FulfillmentResult;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Item;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.LineItem;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Message;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.OrderRef;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.PaymentRef;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Total;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.AwaitingApprovalCheckout;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.payment.PaymentApprovalClient;
import com.copenhagen.core.payment.PaymentApprovalCreationWorker;
import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.orderwebhook.OrderWebhookRepository;
import tools.jackson.databind.ObjectMapper;
import com.copenhagen.core.payment.PaymentApprovalMessages.ApprovalCreatedResponse;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;
import com.copenhagen.core.payment.PaymentCompletionRepository;
import com.copenhagen.core.product.CartProductSnapshot;
import com.copenhagen.core.product.ProductService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 순서 27(Merchant Checkout 생성 요청) — 실 PostgreSQL로 로그인→담기→배송지 등록→주문 검증 흐름을 확인한다.
 *
 * <p>merchant 호출만 대역으로 끊는다(HTTP 의존 제거). Flyway·인증·제약조건·트랜잭션은 전부 실제로 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CORE_TEST_DB_URL", matches = ".+")
class OrderValidationApiIntegrationTest {

    private static final String PASSWORD = "Password1!";
    private static final String MERCHANT_ID = "merchant-a";
    private static final String JWS = "eyJhbGciOiJFUzI1NiJ9.eyJhIjoxfQ.sig";

    @DynamicPropertySource
    static void coreTestDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("CORE_TEST_DB_PASSWORD"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignupService signupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MerchantCheckoutRepository merchantCheckoutRepository;

    @Autowired
    private PaymentCompletionRepository paymentCompletionRepository;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private Map<String, MerchantCheckoutClient> merchantCheckoutClients;

    @MockitoBean
    private PaymentApprovalClient paymentApprovalClient;

    @Autowired
    private CartMerchants cartMerchants;

    @Autowired
    private MerchantsProperties merchantsProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderWebhookRepository webhooks;

    @Autowired
    private PurchaseSuspensionProperties suspension;

    private MerchantCheckoutClient checkoutClient;

    @Autowired
    private PurchaseRequestRepository purchaseRequests;

    @Autowired
    private com.copenhagen.core.purchase.PurchaseWorkflowRepository workflowRepository;

    @Autowired
    private com.copenhagen.core.purchase.PurchaseWorkflowService workflow;

    @Test
    @DisplayName("기본 배송지가 있어도 서버가 대신 고르지 않고 앱의 선택을 기다린다")
    void mcpDefaultAddress_stillWaitsForUserSelection() throws Exception {
        String token = signupAndLogin("handoff1", "테스트");
        addCartItem(token);
        registerAddress(token);
        var row = createHandoff();
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());
        workflow.process(row);
        workflow.process(workflowRepository.require(row.publicId(), row.userId()));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_requests", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
        assertEquals("PENDING_ADDRESS", workflowRepository.require(row.publicId(), row.userId()).status());
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT shipping_address_id FROM purchase_requests", Long.class));
        assertEquals(row.connectionId(), jdbcTemplate.queryForObject(
                "SELECT agent_connection_id FROM purchase_requests", Long.class));
        org.mockito.Mockito.verify(paymentApprovalClient, org.mockito.Mockito.atLeastOnce())
                .notifyPurchase(row.userPublicId(), row.publicId());
        org.mockito.Mockito.verify(checkoutClient, org.mockito.Mockito.never()).create(any(), any());
    }

    @Test
    @DisplayName("기본이 아닌 배송지로 재개하면 그 주소로 checkout이 생기고 기본 배송지는 그대로다")
    void mcpNonDefaultAddress_resumeUsesSelectionAndKeepsDefault() throws Exception {
        String token = signupAndLogin("handoff5", "테스트");
        addCartItem(token);
        String defaultAddress = registerAddress(token);
        String otherAddress = registerAddress(token);
        var row = createHandoff();
        workflow.process(row);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());
        workflow.resume(row.publicId(), row.userId(), UUID.fromString(otherAddress), 1, UUID.randomUUID());
        workflow.process(workflowRepository.require(row.publicId(), row.userId()));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
        assertEquals(otherAddress, jdbcTemplate.queryForObject("""
                SELECT a.public_id FROM purchase_requests p JOIN addresses a ON a.id = p.shipping_address_id
                """, String.class));
        assertEquals(defaultAddress, jdbcTemplate.queryForObject(
                "SELECT public_id FROM addresses WHERE is_default AND deleted_at IS NULL", String.class));
        org.mockito.Mockito.verify(checkoutClient).create(any(),
                eq(OrderValidationService.idempotencyKey(row.publicId(), MERCHANT_ID)));
    }

    @Test
    void mcpMissingAddress_resumeIsOwnedVersionedAndIdempotent() throws Exception {
        String token = signupAndLogin("handoff2", "테스트");
        addCartItem(token);
        var row = createHandoff();
        workflow.process(row);
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
        org.mockito.Mockito.verify(paymentApprovalClient).notifyPurchase(row.userPublicId(), row.publicId());
        UUID addressId = UUID.fromString(registerAddress(token));
        UUID key = UUID.randomUUID();
        workflow.resume(row.publicId(), row.userId(), addressId, 1, key);
        workflow.resume(row.publicId(), row.userId(), addressId, 1, key);
        assertThrows(CoreException.class, () -> workflow.resume(
                row.publicId(), row.userId() + 1, addressId, 1, key));
        assertThrows(CoreException.class, () -> workflow.resume(
                row.publicId(), row.userId(), addressId, 2, key));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_resume_commands", Integer.class));
        assertEquals("MCP", jdbcTemplate.queryForObject("SELECT request_channel FROM purchase_requests", String.class));
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());
        workflow.process(workflowRepository.require(row.publicId(), row.userId()));
        assertEquals("AWAITING_APPROVAL", workflow.view(row.publicId(), row.userId(), row.connectionId()).status());
    }

    @Test
    void mcpResume_rejectsExpiredAndChangedCartBeforeMerchantCalls() throws Exception {
        String token = signupAndLogin("handoff3", "테스트");
        addCartItem(token);
        var row = createHandoff();
        UUID addressId = UUID.fromString(registerAddress(token));
        jdbcTemplate.update("UPDATE carts SET version = version + 1");
        assertEquals(CoreErrorCode.CART_VERSION_CONFLICT, assertThrows(CoreException.class,
                () -> workflow.resume(row.publicId(), row.userId(), addressId, 1, UUID.randomUUID())).errorCode());
        jdbcTemplate.update("UPDATE purchase_requests SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'");
        assertEquals(CoreErrorCode.PURCHASE_REQUEST_EXPIRED, assertThrows(CoreException.class,
                () -> workflow.resume(row.publicId(), row.userId(), addressId, 1, UUID.randomUUID())).errorCode());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
    }

    @Test
    @DisplayName("앱이 쓰는 공개 경로로 배송지를 선택하면 상태·다음 행동이 계약대로 나온다")
    void mcpResume_overHttpSelectsAddressAndKeepsAddressOutOfResponse() throws Exception {
        String token = signupAndLogin("handoff6", "테스트");
        addCartItem(token);
        registerAddress(token);
        String otherAddress = registerAddress(token);
        var row = createHandoff();
        workflow.process(row);

        mockMvc.perform(get("/api/core/v1/purchase-requests/" + row.publicId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_ADDRESS"))
                .andExpect(jsonPath("$.nextAction").value("SELECT_SHIPPING_ADDRESS"))
                .andExpect(jsonPath("$.cartVersion").value(1));

        String resumed = mockMvc.perform(post("/api/core/v1/purchase-requests/" + row.publicId() + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addressId":"%s","cartVersion":1}
                                """.formatted(otherAddress)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATING_CHECKOUTS"))
                .andExpect(jsonPath("$.nextAction").value("WAIT"))
                .andExpect(jsonPath("$.addressId").doesNotExist())
                .andExpect(jsonPath("$.shippingAddress").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        // 선택한 배송지는 DB에만 남고 응답으로 새어 나가지 않는다.
        assertEquals(false, resumed.contains("테헤란로"));
        assertEquals(otherAddress, jdbcTemplate.queryForObject("""
                SELECT a.public_id FROM purchase_requests p JOIN addresses a ON a.id = p.shipping_address_id
                """, String.class));
    }

    @Test
    @DisplayName("다른 사용자의 구매 요청은 배송지를 선택할 수 없고 멱등키 헤더는 필수다")
    void mcpResume_overHttpRejectsOtherUserAndMissingIdempotencyKey() throws Exception {
        String token = signupAndLogin("handoff7", "테스트");
        addCartItem(token);
        String addressId = registerAddress(token);
        var row = createHandoff();
        workflow.process(row);
        String intruder = signupAndLogin("handoff8", "침입자");

        mockMvc.perform(post("/api/core/v1/purchase-requests/" + row.publicId() + "/resume")
                        .header("Authorization", "Bearer " + intruder)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addressId":"%s","cartVersion":1}
                                """.formatted(addressId)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/core/v1/purchase-requests/" + row.publicId() + "/resume")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addressId":"%s","cartVersion":1}
                                """.formatted(addressId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        assertEquals("PENDING_ADDRESS", workflowRepository.require(row.publicId(), row.userId()).status());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_resume_commands", Integer.class));
    }

    @Test
    void mcpMerchantTimeout_retriesSamePurchaseAndIdempotencyKey() throws Exception {
        String token = signupAndLogin("handoff4", "테스트");
        addCartItem(token);
        UUID addressId = UUID.fromString(registerAddress(token));
        var row = createHandoff();
        when(checkoutClient.create(any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE));
        workflow.resume(row.publicId(), row.userId(), addressId, 1, UUID.randomUUID());
        workflow.process(workflowRepository.require(row.publicId(), row.userId()));
        assertEquals("CREATING_CHECKOUTS", workflowRepository.require(row.publicId(), row.userId()).status());
        doReturn(readyCheckout()).when(checkoutClient).create(any(), any());
        workflow.process(workflowRepository.require(row.publicId(), row.userId()));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
        org.mockito.Mockito.verify(checkoutClient, org.mockito.Mockito.times(2)).create(any(),
                eq(OrderValidationService.idempotencyKey(row.publicId(), MERCHANT_ID)));
    }

    private com.copenhagen.core.purchase.PurchaseWorkflowRepository.Row createHandoff() {
        long userId = jdbcTemplate.queryForObject("SELECT id FROM users", Long.class);
        long cartId = jdbcTemplate.queryForObject("SELECT id FROM carts", Long.class);
        long clientId = jdbcTemplate.queryForObject("""
                INSERT INTO oauth_clients (client_id, client_name, client_type, redirect_uris, allowed_scopes)
                VALUES (?, 'Test Agent', 'PUBLIC', '["https://agent.example/callback"]'::jsonb,
                    '["purchase:handoff"]'::jsonb) RETURNING id
                """, Long.class, UUID.randomUUID().toString());
        long connectionId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_connections (public_id, user_id, oauth_client_id, granted_scopes)
                VALUES (?, ?, ?, '["purchase:handoff"]'::jsonb) RETURNING id
                """, Long.class, UUID.randomUUID(), userId, clientId);
        PurchaseRequest purchase = purchaseRequests.insertPendingAddress(UUID.randomUUID(), userId, cartId,
                connectionId, "v1", 1, Instant.now().plusSeconds(1800), Instant.now());
        return workflowRepository.require(purchase.publicId(), userId);
    }

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("""
                TRUNCATE users, carts, cart_items, addresses, purchase_requests, merchant_checkouts,
                    checkout_snapshots, checkout_snapshot_items, checkout_fulfillment_option_snapshots,
                    order_webhook_receipts
                RESTART IDENTITY CASCADE
                """);
        // merchants 는 V7 시드다. 다른 통합 테스트가 TRUNCATE 하고 갈 수 있어 멱등하게 되살린다.
        jdbcTemplate.update("""
                INSERT INTO merchants (id, merchant_name, ucp_profile_url, base_url, business_profile,
                                       active_capabilities, status)
                VALUES (1, 'Copenhagen Demo Store', 'http://ucp-merchant-mock:8080/.well-known/ucp',
                        'http://ucp-merchant-mock:8080', '{}'::jsonb, '[]'::jsonb, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """);

        checkoutClient = mock(MerchantCheckoutClient.class);
        when(merchantCheckoutClients.get(MERCHANT_ID)).thenReturn(checkoutClient);
        when(paymentApprovalClient.create(any(), any())).thenReturn(new ApprovalCreatedResponse(
                "approval-1", "PAYMENT_METHOD_REQUIRED", null,
                Instant.parse("2026-01-01T00:05:00Z"), "APPROVE_IN_APP", List.of()));
        when(productService.captureForCart(eq(MERCHANT_ID), eq("p-1"), any()))
                .thenReturn(new CartProductSnapshot(
                        "p-1", "sku-1", "옵션상품", "http://ucp-merchant-mock:8080/products/p-1",
                        null, 5000L, "KRW",
                        List.of(new CartProductSnapshot.SelectedOption("sku-1", "색상", "화이트"))));
    }

    @Test
    void completionRecovery_databaseLeaseAndConcurrentCallbacksRemainIdempotent() throws Exception {
        AwaitingApprovalCheckout row = prepareAwaitingCheckout("recovery1");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(true, merchantCheckoutRepository.beginCompleting(row.id(), now));
        assertEquals(false, merchantCheckoutRepository.beginCompleting(row.id(), now.plusSeconds(29)));
        assertEquals(1, merchantCheckoutRepository.findCompleting(now.plusSeconds(31)).size());
        assertEquals(true, merchantCheckoutRepository.beginCompleting(row.id(), now.plusSeconds(31)));
        Runnable complete = () -> paymentCompletionRepository.recordCompleted(row.checkoutSnapshotId(), row.userId(),
                row.paymentApprovalId(), "order-1", "payment-1", row.totalAmountMinor(), row.currency(), "카드", now);
        CompletableFuture.allOf(CompletableFuture.runAsync(complete), CompletableFuture.runAsync(complete)).join();
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_transactions", Integer.class));
        assertEquals("COMPLETED", jdbcTemplate.queryForObject("SELECT status FROM merchant_checkouts", String.class));
        webhooks.apply(1L, row.checkoutSnapshotId(), "order-1", "event-shipped", "digest-shipped",
                "SHIPPED", now.plusSeconds(2), now);
        webhooks.apply(1L, row.checkoutSnapshotId(), "order-1", "event-shipped", "digest-shipped",
                "SHIPPED", now.plusSeconds(2), now);
        webhooks.apply(1L, row.checkoutSnapshotId(), "order-1", "event-old", "digest-old", "CREATED", now, now);
        assertEquals("SHIPPED", jdbcTemplate.queryForObject("SELECT status FROM orders", String.class));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_webhook_receipts", Integer.class));
        assertThrows(CoreException.class, () -> webhooks.apply(1L, row.checkoutSnapshotId(), "order-1",
                "event-shipped", "changed-payload", "CANCELLED", now.plusSeconds(3), now));
    }

    @Test
    void failureWithoutTransactionId_isPersistedOnceAndCheckoutLeavesCompleting() throws Exception {
        AwaitingApprovalCheckout row = prepareAwaitingCheckout("recovery2");
        merchantCheckoutRepository.beginCompleting(row.id(), Instant.now());
        for (int attempt = 0; attempt < 2; attempt++) {
            paymentCompletionRepository.recordFailed(row.checkoutSnapshotId(), row.paymentApprovalId(), null,
                    row.totalAmountMinor(), row.currency(), "INVALID_CREDENTIAL", Instant.now());
        }
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_transactions", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions WHERE status = 'FAILED' AND pay_transaction_id IS NULL",
                Integer.class));
        assertEquals("FAILED", jdbcTemplate.queryForObject("SELECT status FROM merchant_checkouts", String.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    @Test
    @DisplayName("판매처 두 곳이면 승인은 한 번만 만들고 checkout 두 행이 같은 승인을 공유한다(이슈 486)")
    void twoMerchants_createSingleApprovalSharedByEveryCheckout() throws Exception {
        String token = signupAndLogin("batch1", "테스트");
        addCartItem(token);
        addCartItemForMerchantB(token);
        String addressId = registerAddress(token);
        MerchantCheckoutClient merchantB = mock(MerchantCheckoutClient.class);
        when(merchantCheckoutClients.get("merchant-b")).thenReturn(merchantB);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());
        when(merchantB.create(any(), any())).thenReturn(readyCheckoutOf("checkout-2"));

        mockMvc.perform(post("/api/core/v1/orders/validations").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(validationBody(2, addressId)))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateApprovalRequest> request = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(paymentApprovalClient, org.mockito.Mockito.times(1))
                .create(request.capture(), any());
        assertEquals(2, request.getValue().items().size());
        assertEquals(26000L, request.getValue().totalAmountMinor());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT payment_approval_id) FROM merchant_checkouts", Integer.class));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_checkouts WHERE status = 'AWAITING_APPROVAL'", Integer.class));
        // 재시도 본문은 구매 요청 단위로 남는다.
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_requests WHERE approval_request IS NOT NULL", Integer.class));
    }

    @Test
    @DisplayName("드레인 중에는 실제 요청 경로에서 503 PURCHASE_SUSPENDED로 반려하고 checkout을 만들지 않는다")
    void approvalSuspended_returns503AndCreatesNoCheckout() throws Exception {
        String token = signupAndLogin("suspend1", "테스트");
        addCartItem(token);
        String addressId = registerAddress(token);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());
        suspension.setApprovalSuspended(true);
        try {
            mockMvc.perform(post("/api/core/v1/orders/validations").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(validationBody(1, addressId)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("PURCHASE_SUSPENDED"))
                    .andExpect(jsonPath("$.nextAction").value("RETRY_LATER"));
        } finally {
            suspension.setApprovalSuspended(false);
        }
        // merchant를 부르기 전에 막아야 승인 없는 checkout이 남지 않는다.
        org.mockito.Mockito.verify(checkoutClient, org.mockito.Mockito.never()).create(any(), any());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
        org.mockito.Mockito.verify(paymentApprovalClient, org.mockito.Mockito.never()).create(any(), any());
    }

    @Test
    @DisplayName("checkout 저장이 실패하면 같은 트랜잭션의 승인 요청 본문도 함께 롤백된다")
    void checkoutInsertFailure_rollsBackStoredApprovalRequest() throws Exception {
        String token = signupAndLogin("rollback1", "테스트");
        addCartItem(token);
        String addressId = registerAddress(token);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());
        mockMvc.perform(post("/api/core/v1/orders/validations").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(validationBody(1, addressId)))
                .andExpect(status().isCreated());

        // 같은 장바구니로 한 번 더 검증하면 merchant가 같은 checkout id를 돌려주고
        // uq_merchant_checkouts_merchant_checkout에 걸려 insertAll이 중간에 실패한다.
        // 승인 요청 본문은 그 앞에서 쓰므로 함께 롤백돼야 한다.
        try {
            mockMvc.perform(post("/api/core/v1/orders/validations").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(validationBody(1, addressId)));
        } catch (Exception expected) {
            // 저장 실패가 예외로 전파되든 5xx 응답으로 매핑되든, 확인 대상은 아래 DB 상태다.
        }

        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_requests", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_requests WHERE approval_request IS NOT NULL", Integer.class));
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_requests ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    void approvalCreationFailure_replaysDurableRequestAndKeepsTheSamePurchase() throws Exception {
        String token = signupAndLogin("recovery3", "테스트");
        addCartItem(token);
        String addressId = registerAddress(token);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());
        when(paymentApprovalClient.create(any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE));
        mockMvc.perform(post("/api/core/v1/orders/validations").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(validationBody(1, addressId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkouts[0].status").value("PENDING_APPROVAL_CREATION"));
        String saved = jdbcTemplate.queryForObject(
                "SELECT approval_request::text FROM purchase_requests", String.class);
        doReturn(new ApprovalCreatedResponse("approval-recovered", "PAYMENT_METHOD_REQUIRED", null,
                Instant.parse("2026-01-01T00:05:00Z"), "APPROVE_IN_APP", List.of()))
                .when(paymentApprovalClient).create(any(), any());
        new PaymentApprovalCreationWorker(merchantCheckoutRepository, paymentApprovalClient, cartMerchants,
                merchantsProperties, objectMapper, Clock.fixed(Instant.parse("2026-01-01T00:20:00Z"), ZoneOffset.UTC))
                .retryPending();
        assertEquals("AWAITING_APPROVAL", jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_checkouts", String.class));
        assertEquals("approval-recovered", jdbcTemplate.queryForObject(
                "SELECT payment_approval_id FROM merchant_checkouts", String.class));
        assertEquals(saved, jdbcTemplate.queryForObject(
                "SELECT approval_request::text FROM purchase_requests", String.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_requests", Integer.class));
    }

    @Test
    void paidCartCleanup_preservesAddedQuantityAndIsIdempotent() throws Exception {
        AwaitingApprovalCheckout row = prepareAwaitingCheckout("cleanup1");
        jdbcTemplate.update("UPDATE cart_items SET quantity = 5");
        jdbcTemplate.update("UPDATE carts SET version = version + 1");
        completeForCleanup(row);
        assertEquals(3, jdbcTemplate.queryForObject("SELECT quantity FROM cart_items", Integer.class));
        int version = jdbcTemplate.queryForObject("SELECT version FROM carts", Integer.class);
        completeForCleanup(row);
        assertEquals(3, jdbcTemplate.queryForObject("SELECT quantity FROM cart_items", Integer.class));
        assertEquals(version, jdbcTemplate.queryForObject("SELECT version FROM carts", Integer.class));
    }

    @Test
    void paidCartCleanup_preservesReAddedLine() throws Exception {
        AwaitingApprovalCheckout row = prepareAwaitingCheckout("cleanup2");
        jdbcTemplate.update("UPDATE cart_items SET public_id = ?", UUID.randomUUID());
        completeForCleanup(row);
        assertEquals(2, jdbcTemplate.queryForObject("SELECT quantity FROM cart_items", Integer.class));
    }

    @Test
    void paidCartCleanup_waitsForEveryMerchantAndRecoversFromWorker() throws Exception {
        AwaitingApprovalCheckout row = prepareAwaitingCheckout("cleanup3");
        // Other integration suites may remove the migration seeds; own this fixture explicitly.
        jdbcTemplate.update("""
                INSERT INTO merchants (id, merchant_name, ucp_profile_url, base_url, business_profile,
                                       active_capabilities, status)
                VALUES (2, 'Cleanup Test Merchant', 'http://merchant-b.example/.well-known/ucp',
                        'http://merchant-b.example', '{}'::jsonb, '[]'::jsonb, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """);
        long purchaseId = jdbcTemplate.queryForObject(
                "SELECT purchase_request_id FROM merchant_checkouts WHERE id = ?", Long.class, row.id());
        jdbcTemplate.update("""
                INSERT INTO merchant_checkouts
                    (purchase_request_id, merchant_id, merchant_checkout_id, checkout_digest, status)
                SELECT purchase_request_id, 2, 'second-checkout', checkout_digest, 'AWAITING_APPROVAL'
                FROM merchant_checkouts WHERE id = ?
                """, row.id());
        completeForCleanup(row);
        assertEquals(2, jdbcTemplate.queryForObject("SELECT quantity FROM cart_items", Integer.class));
        jdbcTemplate.update("UPDATE merchant_checkouts SET status = 'FAILED' WHERE merchant_id = 2");
        completeForCleanup(row);
        assertEquals(2, jdbcTemplate.queryForObject("SELECT quantity FROM cart_items", Integer.class));
        jdbcTemplate.update("UPDATE merchant_checkouts SET status = 'COMPLETED' WHERE merchant_id = 2");
        paidCartCleanup.cleanup(purchaseId, Instant.now());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_items", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_requests WHERE cart_cleaned_at IS NOT NULL", Integer.class));
    }

    private void completeForCleanup(AwaitingApprovalCheckout row) {
        paymentCompletionRepository.recordCompleted(row.checkoutSnapshotId(), row.userId(), row.paymentApprovalId(),
                "cleanup-order", "cleanup-payment", row.totalAmountMinor(), row.currency(), "test", Instant.now());
    }

    @Autowired
    private com.copenhagen.core.payment.PaidCartCleanupRepository paidCartCleanup;

    private AwaitingApprovalCheckout prepareAwaitingCheckout(String loginId) throws Exception {
        String token = signupAndLogin(loginId, "테스트");
        addCartItem(token);
        String addressId = registerAddress(token);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());
        mockMvc.perform(post("/api/core/v1/orders/validations").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(validationBody(1, addressId)))
                .andExpect(status().isCreated());
        return merchantCheckoutRepository.findAwaitingApproval().get(0);
    }

    @Test
    @DisplayName("검증에 성공하면 구매 요청과 merchant 주문서가 저장되고 201이 내려간다")
    void validate_success_persistsPurchaseRequestAndMerchantCheckout() throws Exception {
        String accessToken = signupAndLogin("ordervalid1", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());

        String response = mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkouts[0].merchantId").value(MERCHANT_ID))
                .andExpect(jsonPath("$.checkouts[0].status").value("READY"))
                .andExpect(jsonPath("$.checkouts[0].amount.total").value(13000))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String validationId = JsonPath.read(response, "$.validationId");
        assertEquals("CREATING_CHECKOUTS", jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_requests WHERE public_id = ?::uuid", String.class, validationId));
        // Pay 승인 세션 생성까지 이어져서 CREATING이 아니라 AWAITING_APPROVAL로 반영된다(이슈 410).
        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM merchant_checkouts mc
                JOIN purchase_requests pr ON pr.id = mc.purchase_request_id
                WHERE pr.public_id = ?::uuid AND mc.status = 'AWAITING_APPROVAL'
                  AND mc.payment_approval_id = 'approval-1'
                  AND mc.payment_approval_expires_at = '2026-01-01T00:05:00Z'
                  AND LENGTH(TRIM(mc.checkout_digest)) = 64
                """, Integer.class, validationId));
    }

    @Test
    @DisplayName("Pay 승인 생성이 실패해도 검증 응답은 그대로 201이고 checkout은 CREATING으로 남는다(이슈 410)")
    void validate_paymentApprovalUnavailable_stillReturns201AndKeepsCheckoutCreating() throws Exception {
        String accessToken = signupAndLogin("ordervalid5", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());
        when(paymentApprovalClient.create(any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE));

        String response = mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String validationId = JsonPath.read(response, "$.validationId");
        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM merchant_checkouts mc
                JOIN purchase_requests pr ON pr.id = mc.purchase_request_id
                WHERE pr.public_id = ?::uuid AND mc.status = 'CREATING' AND mc.payment_approval_id IS NULL
                """, Integer.class, validationId));
    }

    @Test
    @DisplayName("검증에 성공하면 주문서 스냅샷과 항목·배송 스냅샷이 함께 저장된다")
    void validate_success_persistsSnapshotRows() throws Exception {
        String accessToken = signupAndLogin("ordersnap1", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());

        String response = mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkouts[0].orderSnapshotId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String orderSnapshotId = JsonPath.read(response, "$.checkouts[0].orderSnapshotId");

        // 응답의 orderSnapshotId로 스냅샷을 찾을 수 있고, 금액·통화·PII·payload가 모두 채워져 있다.
        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM checkout_snapshots
                WHERE public_id = ?::uuid AND total_amount = 13000 AND shipping_fee_amount = 3000
                  AND subtotal_amount = 10000 AND discount_amount = 0 AND tax_amount = 0
                  AND currency = 'KRW' AND expires_at IS NOT NULL
                  AND recipient_name_encrypted IS NOT NULL AND recipient_phone_encrypted IS NOT NULL
                  AND delivery_request_encrypted IS NOT NULL
                  AND merchant_checkout_payload ->> 'id' = 'checkout-1'
                """, Integer.class, orderSnapshotId));

        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM checkout_snapshot_items i
                JOIN checkout_snapshots s ON s.id = i.checkout_snapshot_id
                WHERE s.public_id = ?::uuid AND i.merchant_product_id = 'p-1'
                  AND i.merchant_variant_id = 'sku-1' AND i.quantity = 2
                  AND i.unit_price_amount = 5000 AND i.line_total_amount = 10000
                  AND i.selected_options @> '[{"optionValueId":"sku-1"}]'::jsonb
                """, Integer.class, orderSnapshotId));

        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM checkout_fulfillment_option_snapshots f
                JOIN checkout_snapshots s ON s.id = f.checkout_snapshot_id
                WHERE s.public_id = ?::uuid AND f.delivery_method = 'STANDARD'
                  AND f.shipping_fee_amount = 3000 AND f.fulfillment_group_key = 'checkout-1'
                """, Integer.class, orderSnapshotId));
    }

    @Test
    @DisplayName("반려되면 409와 항목 사유가 내려가고 구매 요청은 FAILED, 주문서는 남지 않는다")
    void validate_rejected_marksFailedAndKeepsNoCheckout() throws Exception {
        String accessToken = signupAndLogin("ordervalid2", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any())).thenReturn(new Checkout(
                "checkout-2", "incomplete", "KRW",
                List.of(new LineItem("line-1", new Item("p-1", "옵션상품", 5000L), 2)),
                null,
                List.of(new Total("subtotal", 10000L), new Total("total", 10000L)),
                List.of(new Message("CHECKOUT_STOCK_INSUFFICIENT", "error", "재고가 부족하다", "line_items[0]")),
                null,
                null));

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.issues[0].code").value("CHECKOUT_STOCK_INSUFFICIENT"));

        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_requests", String.class));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_checkouts", Integer.class));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM checkout_snapshots", Integer.class));
        // 검증이 반려돼도 장바구니는 그대로다(명세 비고).
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cart_items", Integer.class));
    }

    @Test
    @DisplayName("merchant를 부르지 못하면 503이고 구매 요청은 FAILED로 닫힌다")
    void validate_merchantUnavailable_returns503() throws Exception {
        String accessToken = signupAndLogin("ordervalid3", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE));

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("MERCHANT_CHECKOUT_UNAVAILABLE"));

        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM purchase_requests", String.class));
    }

    @Test
    @DisplayName("빈 장바구니는 409 ORDER_CART_EMPTY이고 구매 요청도 만들지 않는다")
    void validate_emptyCart_returns409() throws Exception {
        String accessToken = signupAndLogin("ordervalid4", "홍길동");
        String addressId = registerAddress(accessToken);
        addCartItem(accessToken);
        removeAllCartItems();

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_CART_EMPTY"));

        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_requests", Integer.class));
    }

    @Test
    @DisplayName("승인 확인 후 Merchant Complete 결과를 orders/payment_transactions에 실제 DB로 반영한다(이슈 410)")
    void paymentCompletionPipeline_realDatabase_persistsOrdersAndPaymentTransactions() throws Exception {
        String accessToken = signupAndLogin("ordercomplete1", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isCreated());

        List<AwaitingApprovalCheckout> awaiting = merchantCheckoutRepository.findAwaitingApproval();
        assertEquals(1, awaiting.size());
        AwaitingApprovalCheckout row = awaiting.get(0);
        assertEquals("approval-1", row.paymentApprovalId());
        assertEquals(13000L, row.totalAmountMinor());
        assertEquals("KRW", row.currency());

        // beginCompleting은 조건부 UPDATE다 — 같은 행을 두 번 집으면 두 번째는 진다(워커 동시성 방어).
        assertEquals(true, merchantCheckoutRepository.beginCompleting(row.id(), Instant.now()));
        assertEquals(false, merchantCheckoutRepository.beginCompleting(row.id(), Instant.now()));

        CompleteResult completed = new CompleteResult(
                row.merchantCheckoutId(), "completed",
                new OrderRef("merchant-order-1", "http://merchant-a/orders/1"),
                new PaymentRef("pay-txn-1", "SUCCESS", null));
        paymentCompletionRepository.recordCompleted(
                row.checkoutSnapshotId(), row.userId(), row.paymentApprovalId(),
                completed.order().id(), completed.payment().paymentId(),
                row.totalAmountMinor(), row.currency(), "VISA **** 1234", Instant.now());
        merchantCheckoutRepository.markCompleted(row.id(), Instant.now());

        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM orders
                WHERE checkout_snapshot_id = ? AND user_id = ? AND merchant_order_id = 'merchant-order-1'
                  AND status = 'CREATED'
                """, Integer.class, row.checkoutSnapshotId(), row.userId()));
        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_transactions pt
                JOIN orders o ON o.id = pt.order_id
                WHERE pt.checkout_snapshot_id = ? AND pt.authorization_session_id = 'approval-1'
                  AND pt.pay_transaction_id = 'pay-txn-1' AND pt.status = 'PAID'
                  AND pt.payment_amount = 13000 AND pt.currency = 'KRW'
                  AND pt.payment_label = 'VISA **** 1234'
                  AND o.merchant_order_id = 'merchant-order-1'
                """, Integer.class, row.checkoutSnapshotId()));
        assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_checkouts WHERE id = ?", String.class, row.id()));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_checkouts WHERE id = ? AND completed_at IS NULL",
                Integer.class, row.id()));
    }

    @Test
    @DisplayName("승인 거부·만료·결제 실패가 각각 CANCELLED/EXPIRED/FAILED와 실패 payment_transactions로 반영된다(이슈 410)")
    void paymentCompletionPipeline_failureBranches_updateCheckoutAndTransactionStatus() throws Exception {
        String accessToken = signupAndLogin("ordercomplete2", "홍길동");
        addCartItem(accessToken);
        String addressId = registerAddress(accessToken);
        when(checkoutClient.create(any(), any())).thenReturn(readyCheckout());

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationBody(1, addressId)))
                .andExpect(status().isCreated());
        AwaitingApprovalCheckout row = merchantCheckoutRepository.findAwaitingApproval().get(0);

        paymentCompletionRepository.recordFailed(
                row.checkoutSnapshotId(), row.paymentApprovalId(), "pay-txn-2",
                row.totalAmountMinor(), row.currency(), "CARD_DECLINED", Instant.now());
        merchantCheckoutRepository.markFailed(row.id(), "CARD_DECLINED", Instant.now());
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_checkouts WHERE id = ?", String.class, row.id()));
        assertEquals(1, (int) jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_transactions
                WHERE checkout_snapshot_id = ? AND status = 'FAILED' AND failure_code = 'CARD_DECLINED'
                  AND order_id IS NULL
                """, Integer.class, row.checkoutSnapshotId()));

        merchantCheckoutRepository.markCancelled(row.id(), "USER_REJECTED", Instant.now());
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_checkouts WHERE id = ?", String.class, row.id()));
        // 각 종료 전이는 승인 대기 행에만 적용되고 이미 끝난 결과는 덮어쓰지 않는다.
        jdbcTemplate.update("UPDATE merchant_checkouts SET status = 'AWAITING_APPROVAL' WHERE id = ?", row.id());
        merchantCheckoutRepository.markCancelled(row.id(), "USER_REJECTED", Instant.now());
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_checkouts WHERE id = ?", String.class, row.id()));
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_checkouts WHERE id = ? AND cancelled_at IS NULL",
                Integer.class, row.id()));

        merchantCheckoutRepository.markExpired(row.id(), Instant.now());
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_checkouts WHERE id = ?", String.class, row.id()));
        jdbcTemplate.update("UPDATE merchant_checkouts SET status = 'AWAITING_APPROVAL' WHERE id = ?", row.id());
        merchantCheckoutRepository.markExpired(row.id(), Instant.now());
        assertEquals("EXPIRED", jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_checkouts WHERE id = ?", String.class, row.id()));
    }

    @Test
    @DisplayName("인증 없는 호출은 401이다")
    void validate_withoutToken_returns401() throws Exception {
        String body = """
                {"cartId":"%s","cartVersion":1,"addressId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    private static Checkout readyCheckout() {
        return new Checkout(
                "checkout-1",
                "ready_for_complete",
                "KRW",
                List.of(new LineItem("line-1", new Item("p-1", "옵션상품", 5000L), 2)),
                new FulfillmentResult("shipping", "available", 3000L),
                List.of(
                        new Total("subtotal", 10000L),
                        new Total("fulfillment", 3000L),
                        new Total("total", 13000L)),
                null,
                "2026-01-01T00:15:00Z",
                JWS);
    }

    private String validationBody(int cartVersion, String addressId) {
        String cartId = jdbcTemplate.queryForObject(
                "SELECT public_id FROM carts WHERE status = 'ACTIVE'", String.class);
        return """
                {"cartId":"%s","cartVersion":%d,"addressId":"%s"}
                """.formatted(cartId, cartVersion, addressId);
    }

    private void addCartItem(String accessToken) throws Exception {
        mockMvc.perform(post("/api/core/v1/cart/items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":0,"productId":"p-1","merchantId":"merchant-a",
                                 "optionValueIds":["sku-1"],"quantity":2}
                                """))
                .andExpect(status().isCreated());
    }

    /** merchant-b는 V8 시드의 ref-id 2다. 장바구니가 두 판매처로 갈려야 배치 승인이 검증된다. */
    private void addCartItemForMerchantB(String accessToken) throws Exception {
        // merchants 는 V8 시드다. 다른 통합 테스트가 TRUNCATE 하고 갈 수 있어 멱등하게 되살린다.
        jdbcTemplate.update("""
                INSERT INTO merchants (id, merchant_name, ucp_profile_url, base_url, business_profile,
                                       active_capabilities, status)
                VALUES (2, '노을상점', 'http://ucp-merchant-mock:8080/merchant-b/.well-known/ucp',
                        'http://ucp-merchant-mock:8080/merchant-b', '{}'::jsonb, '[]'::jsonb, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """);
        when(productService.captureForCart(eq("merchant-b"), eq("p-2"), any()))
                .thenReturn(new CartProductSnapshot(
                        "p-2", "sku-2", "노을상점 상품", "http://ucp-merchant-mock:8080/products/p-2",
                        null, 13000L, "KRW",
                        List.of(new CartProductSnapshot.SelectedOption("sku-2", "색상", "블랙"))));
        mockMvc.perform(post("/api/core/v1/cart/items")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":1,"productId":"p-2","merchantId":"merchant-b",
                                 "optionValueIds":["sku-2"],"quantity":1}
                                """))
                .andExpect(status().isCreated());
    }

    private static Checkout readyCheckoutOf(String checkoutId) {
        return new Checkout(checkoutId, "ready_for_complete", "KRW",
                List.of(new LineItem("line-2", new Item("p-2", "노을상점 상품", 13000L), 1)),
                new FulfillmentResult("shipping", "available", 0L),
                List.of(new Total("subtotal", 13000L), new Total("fulfillment", 0L), new Total("total", 13000L)),
                null, "2026-01-01T00:15:00Z", JWS);
    }

    private void removeAllCartItems() {
        jdbcTemplate.update("DELETE FROM cart_items");
    }

    private String registerAddress(String accessToken) throws Exception {
        String response = mockMvc.perform(post("/api/core/v1/addresses")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"홍길동","recipientPhone":"01012345678",
                                 "postalCode":"06236","addressLine1":"서울시 강남구 테헤란로 1",
                                 "addressLine2":"5층","deliveryRequest":"문 앞에 놓아주세요"}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.addressId");
    }

    private String signupAndLogin(String loginId, String name) throws Exception {
        signupService.signup(loginId, PASSWORD, loginId + "@example.com", name);
        String response = mockMvc.perform(post("/api/core/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s","deviceId":"device-1","deviceName":"iPhone 15"}
                                """.formatted(loginId, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
