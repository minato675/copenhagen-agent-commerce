package com.copenhagen.merchantmock.checkout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CompleteResult;
import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import com.copenhagen.merchantmock.catalog.CatalogFixture;
import com.copenhagen.merchantmock.catalog.CatalogStore;
import com.copenhagen.merchantmock.order.InMemoryOrderRepository;
import com.copenhagen.merchantmock.order.OrderService;
import com.copenhagen.merchantmock.order.RecordingOrderEventListener;
import com.copenhagen.merchantmock.paymenthandler.PayClient;
import com.copenhagen.merchantmock.paymenthandler.PayProperties;
import com.copenhagen.merchantmock.paymenthandler.PaymentRequest;
import com.copenhagen.merchantmock.paymenthandler.PaymentResult;
import com.copenhagen.merchantmock.paymenthandler.PaymentService;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.security.CheckoutDigest;
import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.harness;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.line;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.request;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.requestFor;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.runningShoesToSeoul;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkout Complete(Jira 176·177, DESIGN.md 7.1절·6.5절). 저장소는 인메모리, Pay 는 가짜다.
 *
 * <p>필수 테스트 7·8·9번(12절)이 여기 있다. Pay 성공 전 주문 금지, 성공 후 주문 한 번, 중복 호출에 기존 주문.
 * 177 의 검증 다섯도 여기다 — 하나라도 걸리면 Pay 호출 횟수가 0 이어야 한다.
 */
class CheckoutCompletionServiceTest {

    private static final String CREDENTIAL = "credential-0001";

    private Harness checkout;
    private StubPayClient pay;
    private InMemoryOrderRepository orders;
    private RecordingOrderEventListener events;
    private List<Duration> slept;
    private CheckoutCompletionService service;

    @BeforeEach
    void setUp() {
        checkout = harness();
        pay = new StubPayClient();
        orders = new InMemoryOrderRepository();
        events = new RecordingOrderEventListener();
        slept = new ArrayList<>();
        service = build(checkout.clock());
    }

    private CheckoutCompletionService build(Clock clock) {
        CatalogStore catalog = new CatalogStore(CatalogFixture::seed);
        catalog.load();
        PaymentService payments = new PaymentService(checkout.repository(), pay, checkout.properties(),
                new PayProperties("http://pay-service:8080", "test-token", "", null, null, null, 0), clock);
        OrderService orderService = new OrderService(orders, catalog, checkout.objectMapper(),
                checkout.properties(), clock, events);
        return new CheckoutCompletionService(checkout.repository(), payments, orderService,
                checkout.objectMapper(), checkout.properties(), checkout.scenario(), slept::add,
                TransactionOperations.withoutTransaction(), clock);
    }

    private String readyCheckout() {
        return checkout.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-complete").id();
    }

    private static PaymentResult success(String paymentId) {
        return new PaymentResult(PaymentStatus.SUCCESS, paymentId, "pg-1", null, "CREATE_ORDER");
    }

    @Test
    @DisplayName("SUCCESS 면 주문이 생기고 completed 응답이 Core 확정본 4.8 모양이다")
    void complete_paymentSuccess_createsOrderAndReturnsCompleted() {
        // given
        String id = readyCheckout();
        pay.result = success("pay-1");

        // when
        CompleteResult result = service.complete("merchant-a", id, CREDENTIAL);

        // then
        assertEquals(id, result.id());
        assertEquals("completed", result.status());
        assertNotNull(result.order().id());
        assertEquals("http://ucp-merchant-mock:8080/api/ucp/v1/merchant-a/orders/" + result.order().id(),
                result.order().permalinkUrl());
        assertEquals("pay-1", result.payment().paymentId());
        assertEquals("SUCCESS", result.payment().status());
        assertNull(result.payment().errorCode());

        CheckoutSession row = checkout.repository().findById(id).orElseThrow();
        assertEquals(CheckoutStatus.COMPLETED, row.status());
        assertEquals(PaymentStatus.SUCCESS, row.paymentStatus());
        assertNotNull(row.completionResponseJson());
        assertEquals(1, events.created.size(), "주문 사건 한 번 → 웹훅 한 번");
        assertTrue(orders.findByCheckoutSessionId(id).isPresent());
    }

    @Test
    @DisplayName("Pay 요청은 내 체크아웃의 금액·통화와 JWS 해시로 만든다. Core 가 보낸 값은 없다")
    void complete_paymentSuccess_buildsChargeFromAuthoritativeCheckout() {
        // given
        String id = readyCheckout();
        String jws = checkout.repository().findById(id).orElseThrow().merchantAuthorizationJws();
        pay.result = success("pay-1");

        // when
        service.complete("merchant-a", id, CREDENTIAL);

        // then
        PaymentRequest sent = pay.requests.get(0);
        assertEquals("merchant-a", sent.merchantId());
        assertEquals(id, sent.businessCheckoutId());
        assertEquals(CheckoutDigest.of(jws), sent.checkoutHash(), "checkoutHash 는 JWS 문자열의 해시다(6.3절)");
        assertEquals(92000L, sent.amountMinor());
        assertEquals("KRW", sent.currency());
        assertEquals(CREDENTIAL, sent.credentialId());
        assertEquals(new PaymentRequest(
                "merchant-a", id, CheckoutDigest.of(jws), 92000L, "KRW", CREDENTIAL)
                .idempotencyKey(), sent.idempotencyKey());
    }

    @Test
    @DisplayName("같은 키로 다시 오면 결제하지 않고 저장된 응답을 그대로 재생한다(필수 테스트 9번)")
    void complete_calledTwice_replaysStoredResponseWithoutSecondCharge() {
        // given
        String id = readyCheckout();
        pay.result = success("pay-1");
        CompleteResult first = service.complete("merchant-a", id, CREDENTIAL);

        // when
        CompleteResult second = service.complete("merchant-a", id, CREDENTIAL);

        // then
        assertEquals(first, second);
        assertEquals(1, pay.requests.size(), "Pay 는 한 번만 불린다");
        assertEquals(1, events.created.size(), "주문도 하나다(필수 테스트 8번)");
    }

    @Test
    @DisplayName("FAILED 면 주문을 만들지 않고 ready_for_complete 로 되돌린다(필수 테스트 7번)")
    void complete_paymentFailed_revertsToReadyWithoutOrder() {
        // given
        String id = readyCheckout();
        pay.result = new PaymentResult(PaymentStatus.FAILED, "pay-1", null, "PG_DECLINED", "SHOW_PAYMENT_FAILURE");

        // when
        CompleteResult result = service.complete("merchant-a", id, CREDENTIAL);

        // then
        assertEquals("ready_for_complete", result.status());
        assertNull(result.order());
        assertEquals("FAILED", result.payment().status());
        assertEquals("PG_DECLINED", result.payment().errorCode());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, checkout.repository().findById(id).orElseThrow().status());
        assertTrue(orders.findByCheckoutSessionId(id).isEmpty());
        assertTrue(events.created.isEmpty());
    }

    @Test
    @DisplayName("FAILED 뒤 새 Credential 로 다시 오면 새 청구다. 재승인 재시도가 막히지 않는다(8.1절)")
    void complete_afterFailed_newCredentialChargesAgain() {
        // given
        String id = readyCheckout();
        pay.result = new PaymentResult(PaymentStatus.FAILED, null, null, "PG_DECLINED", null);
        service.complete("merchant-a", id, CREDENTIAL);
        pay.result = success("pay-2");

        // when
        CompleteResult retry = service.complete("merchant-a", id, "credential-0002");

        // then
        assertEquals("completed", retry.status());
        assertEquals(2, pay.requests.size());
        assertEquals(new PaymentRequest(
                "merchant-a", id, CheckoutDigest.of(
                        checkout.repository().findById(id).orElseThrow().merchantAuthorizationJws()),
                92000L, "KRW", "credential-0002").idempotencyKey(),
                pay.requests.get(1).idempotencyKey());
    }

    @Test
    @DisplayName("UNKNOWN 이면 주문을 만들지 않고 complete_in_progress 로 남긴다. 재청구도 없다")
    void complete_paymentUnknown_staysInProgressWithoutOrder() {
        // given
        String id = readyCheckout();
        pay.result = new PaymentResult(PaymentStatus.UNKNOWN, null, null, "PAY_UNREACHABLE", null);

        // when
        CompleteResult result = service.complete("merchant-a", id, CREDENTIAL);

        // then
        assertEquals("complete_in_progress", result.status());
        assertNull(result.order());
        assertEquals("UNKNOWN", result.payment().status());
        assertEquals(CheckoutStatus.COMPLETE_IN_PROGRESS, checkout.repository().findById(id).orElseThrow().status());
        assertTrue(orders.findByCheckoutSessionId(id).isEmpty());
        assertEquals(1, pay.requests.size());
    }

    @Test
    @DisplayName("진행 중인 건에 다시 오면 결제하지 않고 진행 중 응답을 돌려준다(Jira 176 완료 조건)")
    void complete_whileInProgress_replaysInProgressWithoutCharge() {
        // given
        String id = readyCheckout();
        pay.result = new PaymentResult(PaymentStatus.UNKNOWN, null, null, "PAY_UNREACHABLE", null);
        service.complete("merchant-a", id, CREDENTIAL);

        // when
        CompleteResult again = service.complete("merchant-a", id, CREDENTIAL);

        // then
        assertEquals("complete_in_progress", again.status());
        assertEquals("UNKNOWN", again.payment().status());
        assertEquals(1, pay.requests.size());
    }

    @Test
    @DisplayName("늦게 확정된 SUCCESS 는 주문을 만들고 completed 로 옮긴다(8.8절)")
    void paymentResolved_lateSuccess_createsOrderAndCompletes() {
        // given
        String id = readyCheckout();
        pay.result = new PaymentResult(PaymentStatus.UNKNOWN, null, null, "PAY_UNREACHABLE", null);
        service.complete("merchant-a", id, CREDENTIAL);

        // when
        checkout.repository().resolvePayment(id, PaymentStatus.SUCCESS, "pay-late");
        service.paymentResolved(id, PaymentStatus.SUCCESS, "pay-late");

        // then
        CheckoutSession row = checkout.repository().findById(id).orElseThrow();
        assertEquals(CheckoutStatus.COMPLETED, row.status());
        assertTrue(row.completionResponseJson().contains("\"paymentId\":\"pay-late\""));
        assertTrue(orders.findByCheckoutSessionId(id).isPresent());
        assertEquals(1, events.created.size());

        CompleteResult replay = service.complete("merchant-a", id, CREDENTIAL);
        assertEquals("completed", replay.status());
        assertEquals("pay-late", replay.payment().paymentId());
        assertEquals(1, pay.requests.size(), "확정 뒤 재요청도 재청구가 아니다");
    }

    @Test
    @DisplayName("늦게 확정된 FAILED 는 ready_for_complete 로 되돌린다")
    void paymentResolved_lateFailed_revertsToReady() {
        // given
        String id = readyCheckout();
        pay.result = new PaymentResult(PaymentStatus.UNKNOWN, null, null, "PAY_UNREACHABLE", null);
        service.complete("merchant-a", id, CREDENTIAL);

        // when
        checkout.repository().resolvePayment(id, PaymentStatus.FAILED, "pay-late");
        service.paymentResolved(id, PaymentStatus.FAILED, "pay-late");

        // then
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, checkout.repository().findById(id).orElseThrow().status());
        assertTrue(orders.findByCheckoutSessionId(id).isEmpty());
    }

    @Test
    @DisplayName("조회(GET)는 completed 면 order 와 payment 를 얹는다. Core 확정본 6.1 의 응답 유실 복구 경로")
    void get_afterCompleted_carriesOrderAndPayment() {
        // given
        String id = readyCheckout();
        pay.result = success("pay-1");
        CompleteResult completed = service.complete("merchant-a", id, CREDENTIAL);

        // when
        Checkout fetched = checkout.service().get("merchant-a", id);

        // then
        assertEquals("completed", fetched.status());
        assertEquals(completed.order(), fetched.order());
        assertEquals(completed.payment(), fetched.payment());
        assertNotNull(fetched.merchantAuthorization(), "스냅샷 자체는 그대로다");
    }

    @Test
    @DisplayName("조회(GET)는 complete_in_progress 면 payment 상태만 얹는다")
    void get_whileInProgress_carriesPaymentStatus() {
        // given
        String id = readyCheckout();
        pay.result = new PaymentResult(PaymentStatus.UNKNOWN, null, null, "PAY_UNREACHABLE", null);
        service.complete("merchant-a", id, CREDENTIAL);

        // when
        Checkout fetched = checkout.service().get("merchant-a", id);

        // then
        assertEquals("complete_in_progress", fetched.status());
        assertNull(fetched.order());
        assertEquals("UNKNOWN", fetched.payment().status());
    }

    @Test
    @DisplayName("incomplete 체크아웃은 409 CHECKOUT_INCOMPLETE 이고 Pay 를 부르지 않는다")
    void complete_incompleteCheckout_rejectedBeforePay() {
        // given
        String id = checkout.service().create("merchant-a", request(null, line("product-101", "product-101-270", 1)),
                "checkout-create-incomplete").id();

        // when
        ApiException thrown = assertThrows(ApiException.class, () -> service.complete("merchant-a", id, CREDENTIAL));

        // then
        assertEquals("CHECKOUT_INCOMPLETE", thrown.errorCode());
        assertEquals(409, thrown.status().value());
        assertEquals("incomplete", thrown.currentState());
        assertEquals("UPDATE_CHECKOUT_INPUT", thrown.nextAction());
        assertTrue(pay.requests.isEmpty());
    }

    @Test
    @DisplayName("purchase_request_id 없이 만든 체크아웃은 incomplete 라 Complete 도 막힌다(6.4절)")
    void complete_withoutPurchaseRequestId_rejectedBeforePay() {
        // given
        String id = checkout.service().create("merchant-a",
                requestFor(CheckoutTestSupport.SEOUL, null, line("product-101", "product-101-270", 1)),
                "checkout-create-no-pr").id();

        // when
        ApiException thrown = assertThrows(ApiException.class, () -> service.complete("merchant-a", id, CREDENTIAL));

        // then
        assertEquals("CHECKOUT_INCOMPLETE", thrown.errorCode());
        assertTrue(pay.requests.isEmpty());
    }

    @Test
    @DisplayName("취소된 체크아웃은 409 CHECKOUT_NOT_MODIFIABLE 이다")
    void complete_canceledCheckout_rejected() {
        // given
        String id = readyCheckout();
        checkout.service().cancel("merchant-a", id);

        // when
        ApiException thrown = assertThrows(ApiException.class, () -> service.complete("merchant-a", id, CREDENTIAL));

        // then
        assertEquals("CHECKOUT_NOT_MODIFIABLE", thrown.errorCode());
        assertEquals("canceled", thrown.currentState());
        assertTrue(pay.requests.isEmpty());
    }

    @Test
    @DisplayName("만료된 체크아웃은 409 CHECKOUT_EXPIRED 이고 Pay 를 부르지 않는다. 스윕 워커가 아직 안 닫은 창이다")
    void complete_expiredCheckout_rejectedBeforePay() {
        // given
        String id = readyCheckout();
        CheckoutCompletionService later = build(Clock.fixed(
                Instant.parse("2026-09-10T04:05:00Z"), ZoneId.of("Asia/Seoul")));

        // when
        ApiException thrown = assertThrows(ApiException.class, () -> later.complete("merchant-a", id, CREDENTIAL));

        // then
        assertEquals("CHECKOUT_EXPIRED", thrown.errorCode());
        assertEquals(409, thrown.status().value());
        assertEquals("RESTART_CHECKOUT", thrown.nextAction());
        assertTrue(pay.requests.isEmpty());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, checkout.repository().findById(id).orElseThrow().status(),
                "상태는 건드리지 않는다. 닫는 것은 스윕 워커다");
    }

    @Test
    @DisplayName("없는 체크아웃은 404 다")
    void complete_unknownCheckout_notFound() {
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.complete("merchant-a", "nope", CREDENTIAL));

        assertEquals("CHECKOUT_NOT_FOUND", thrown.errorCode());
        assertTrue(pay.requests.isEmpty());
    }

    @Test
    @DisplayName("다른 몰의 경로로 온 Complete 는 404 이고 Pay 를 부르지 않는다 (393)")
    void complete_otherMall_notFoundBeforePay() {
        // given: merchant-a 에 만든 ready_for_complete 체크아웃
        String id = readyCheckout();

        // when: 같은 id 를 merchant-b 경로로 완료하려 한다
        ApiException thrown = assertThrows(ApiException.class, () -> service.complete("merchant-b", id, CREDENTIAL));

        // then: 존재를 드러내지 않고 결제도 시작하지 않는다
        assertEquals("CHECKOUT_NOT_FOUND", thrown.errorCode());
        assertEquals(404, thrown.status().value());
        assertTrue(pay.requests.isEmpty());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, checkout.repository().findById(id).orElseThrow().status(),
                "남의 몰 요청이 원래 몰의 체크아웃을 건드리지 않는다");
    }

    @Test
    @DisplayName("credentialId 가 비었거나 100자를 넘으면 400 INVALID_REQUEST 다")
    void complete_badCredentialId_invalidRequest() {
        // given
        String id = readyCheckout();

        // when & then
        ApiException blank = assertThrows(ApiException.class, () -> service.complete("merchant-a", id, " "));
        assertEquals("INVALID_REQUEST", blank.errorCode());
        assertEquals(400, blank.status().value());

        ApiException tooLong = assertThrows(ApiException.class,
                () -> service.complete("merchant-a", id, "c".repeat(101)));
        assertEquals("INVALID_REQUEST", tooLong.errorCode());
        assertTrue(pay.requests.isEmpty());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, checkout.repository().findById(id).orElseThrow().status());
    }

    @Test
    @DisplayName("시나리오 COMPLETE_TIMEOUT_BEFORE_PROCESS 는 결제 전에 기다린다")
    void complete_timeoutBeforeProcessScenario_sleepsBeforeCharge() {
        // given
        String id = readyCheckout();
        checkout.scenario().set(Scenario.COMPLETE_TIMEOUT_BEFORE_PROCESS);
        pay.result = success("pay-1");

        // when
        CompleteResult result = service.complete("merchant-a", id, CREDENTIAL);

        // then
        assertEquals("completed", result.status());
        assertEquals(List.of(Duration.ofSeconds(5)), slept, "fixture 의 checkout.scenario-delay 는 PT5S 다");
    }

    @Test
    @DisplayName("시나리오 COMPLETE_TIMEOUT_AFTER_ORDER 는 주문을 만든 뒤 응답 직전에 기다린다")
    void complete_timeoutAfterOrderScenario_sleepsAfterOrder() {
        // given
        String id = readyCheckout();
        checkout.scenario().set(Scenario.COMPLETE_TIMEOUT_AFTER_ORDER);
        pay.result = success("pay-1");

        // when
        CompleteResult result = service.complete("merchant-a", id, CREDENTIAL);

        // then
        assertEquals("completed", result.status());
        assertEquals(List.of(Duration.ofSeconds(5)), slept);
        assertTrue(orders.findByCheckoutSessionId(id).isPresent(), "주문은 실제로 생겼다. Core 만 모른다");
    }

    @Test
    @DisplayName("시나리오가 꺼져 있으면 기다리지 않는다")
    void complete_normalScenario_neverSleeps() {
        // given
        String id = readyCheckout();
        pay.result = success("pay-1");

        // when
        service.complete("merchant-a", id, CREDENTIAL);

        // then
        assertTrue(slept.isEmpty());
    }

    private static final class StubPayClient implements PayClient {

        final List<PaymentRequest> requests = new ArrayList<>();
        PaymentResult result;

        @Override
        public PaymentResult charge(PaymentRequest request) {
            requests.add(request);
            return result;
        }

        @Override
        public PaymentResult lookup(String merchantId, String paymentId) {
            throw new UnsupportedOperationException();
        }
    }
}
