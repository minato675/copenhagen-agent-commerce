package com.copenhagen.merchantmock.paymenthandler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.checkout.CheckoutRepository;
import com.copenhagen.merchantmock.checkout.CheckoutSession;
import com.copenhagen.merchantmock.checkout.PaymentRecheck;
import com.copenhagen.merchantmock.security.CheckoutDigest;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 결제 UNKNOWN 재조회 워커(Jira 183). 두 경로가 갈리는 지점과 결과 반영만 본다.
 *
 * <p>집는 SQL 자체는 JdbcCheckoutRepositoryPostgresTest 몫이다. 여기서는 저장소가 집어 준 행을 받아
 * 무엇을 묻고 무엇을 기록하는지를 본다.
 */
class PaymentRecheckWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T04:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final OffsetDateTime NOW_SEOUL = OffsetDateTime.ofInstant(NOW, SEOUL);
    private static final String JWS = "eyJhbGciOiJFUzI1NiJ9.eyJjaGVja291dElkIjoiY2hlY2tvdXQtMSJ9.sig";
    private static final int MAX_ATTEMPTS = 10;

    private RecheckRepository repository;
    private RecheckPayClient client;
    private final List<String> resolved = new ArrayList<>();
    private final PaymentResolutionListener listener =
            (checkoutId, status, paymentId) -> resolved.add(checkoutId + ":" + status + ":" + paymentId);

    @BeforeEach
    void setUp() {
        repository = new RecheckRepository();
        client = new RecheckPayClient();
    }

    private PaymentRecheckWorker worker() {
        return new PaymentRecheckWorker(repository, client, listener, MerchantPropertiesFixture.defaults(),
                payProperties(), Clock.fixed(NOW, SEOUL), 100, Duration.ofMinutes(2));
    }

    private static PayProperties payProperties() {
        return new PayProperties("http://pay-service:8080", "test-token", "", null, null,
                Duration.ofSeconds(30), MAX_ATTEMPTS);
    }

    private static PaymentRecheck row(String payPaymentId, int attemptCount) {
        return new PaymentRecheck("checkout-1", "merchant-a", payPaymentId, "cred-1", JWS, 92000L, "KRW", attemptCount);
    }

    @Test
    @DisplayName("paymentId 를 알면 GET 으로 묻는다")
    void recheck_knownPaymentId_looksUpById() {
        // given
        repository.claimed = List.of(row("pay-1", 1));
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-1", "pg-1", null, null);

        // when
        worker().recheck();

        // then
        assertEquals(List.of("lookup:pay-1"), client.calls);
    }

    @Test
    @DisplayName("paymentId 가 없으면 같은 멱등 키와 같은 값으로 다시 POST 한다. 재청구가 아니다")
    void recheck_unknownPaymentId_repostsWithSameIdempotencyKey() {
        // given
        repository.claimed = List.of(row(null, 1));
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-9", "pg-9", null, null);

        // when
        worker().recheck();

        // then
        assertEquals(1, client.requests.size());
        PaymentRequest sent = client.requests.get(0);
        assertEquals(new PaymentRequest(
                "merchant-a", "checkout-1", CheckoutDigest.of(JWS), 92000L, "KRW", "cred-1")
                .idempotencyKey(), sent.idempotencyKey());
        assertEquals("merchant-a", sent.merchantId());
        assertEquals("checkout-1", sent.businessCheckoutId());
        assertEquals(CheckoutDigest.of(JWS), sent.checkoutHash(), "checkoutHash 는 JWS 에서 다시 계산한다");
        assertEquals(92000L, sent.amountMinor());
        assertEquals("KRW", sent.currency());
        assertEquals("cred-1", sent.credentialId());
    }

    @Test
    @DisplayName("SUCCESS 로 확정되면 UNKNOWN 행을 닫는다")
    void recheck_resolvedSuccess_closesRow() {
        // given
        repository.claimed = List.of(row("pay-1", 1));
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-1", "pg-1", null, null);

        // when
        worker().recheck();

        // then
        assertEquals(List.of("resolve:checkout-1:SUCCESS:pay-1"), repository.calls);
        assertEquals(List.of("checkout-1:SUCCESS:pay-1"), resolved, "주문 생성과 completed 전이는 리스너 몫이다(176)");
    }

    @Test
    @DisplayName("이미 누가 확정한 행이면 리스너를 부르지 않는다")
    void recheck_alreadyResolved_doesNotNotifyListener() {
        // given
        repository.claimed = List.of(row("pay-1", 1));
        repository.resolveResult = false;
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-1", "pg-1", null, null);

        // when
        worker().recheck();

        // then
        assertTrue(resolved.isEmpty());
    }

    @Test
    @DisplayName("리스너가 터져도 배치는 계속되고 결제 상태는 확정된 채 남는다")
    void recheck_listenerThrows_continuesBatch() {
        // given
        repository.claimed = List.of(row("pay-1", 1), row("pay-2", 1));
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-1", "pg-1", null, null);
        PaymentRecheckWorker worker = new PaymentRecheckWorker(repository, client,
                (checkoutId, status, paymentId) -> {
                    throw new IllegalStateException("주문 생성 실패");
                },
                MerchantPropertiesFixture.defaults(), payProperties(), Clock.fixed(NOW, SEOUL), 100,
                Duration.ofMinutes(2));

        // when
        worker.recheck();

        // then
        assertEquals(2, repository.calls.size(), "두 건 다 resolve 까지 갔다");
    }

    @Test
    @DisplayName("FAILED 로 확정돼도 닫는다. 돈이 움직이지 않은 것이 확인된 것이다")
    void recheck_resolvedFailed_closesRow() {
        // given
        repository.claimed = List.of(row("pay-1", 1));
        client.result = new PaymentResult(PaymentStatus.FAILED, "pay-1", null, "DECLINED", null);

        // when
        worker().recheck();

        // then
        assertEquals(List.of("resolve:checkout-1:FAILED:pay-1"), repository.calls);
    }

    @Test
    @DisplayName("여전히 UNKNOWN 이면 다음에 볼 시각만 민다")
    void recheck_stillUnknown_onlyPushesNextAttempt() {
        // given. 두 번 집혔으니 다음은 30초 × 2^1 = 60초 뒤다
        repository.claimed = List.of(row("pay-1", 2));
        client.result = PaymentResult.unknown("PAY_HTTP_503");

        // when
        worker().recheck();

        // then
        assertEquals(List.of("schedule:checkout-1@" + NOW_SEOUL.plusSeconds(60)), repository.calls);
    }

    @Test
    @DisplayName("PROCESSING 도 아직 결과가 아니라 미룬다")
    void recheck_stillProcessing_onlyPushesNextAttempt() {
        // given
        repository.claimed = List.of(row("pay-1", 1));
        client.result = new PaymentResult(PaymentStatus.PROCESSING, "pay-1", null, null, null);

        // when
        worker().recheck();

        // then
        assertEquals(List.of("schedule:checkout-1@" + NOW_SEOUL.plusSeconds(30)), repository.calls);
    }

    @Test
    @DisplayName("IDEMPOTENCY_CONFLICT 는 결제 결과가 아니라 내 버그다. FAILED 로 닫지 않는다")
    void recheck_idempotencyConflict_keepsRowUnknown() {
        // given
        repository.claimed = List.of(row(null, 1));
        client.result = PaymentResult.failed(HttpPayClient.ERROR_IDEMPOTENCY_CONFLICT);

        // when
        worker().recheck();

        // then
        assertTrue(repository.calls.stream().noneMatch(c -> c.startsWith("resolve:")),
                "확정하지 않는다");
        assertEquals(List.of("schedule:checkout-1@" + NOW_SEOUL.plusSeconds(30)), repository.calls);
    }

    @Test
    @DisplayName("한도까지 확인하고도 모르면 더 묻지 않고 스캔에서 빠지게 둔다")
    void recheck_attemptsExhausted_stopsRescheduling() {
        // given
        repository.claimed = List.of(row("pay-1", MAX_ATTEMPTS));
        client.result = PaymentResult.unknown("PAY_HTTP_503");

        // when
        worker().recheck();

        // then
        assertTrue(repository.calls.isEmpty(), "상태를 바꾸지도 다음 시각을 밀지도 않는다");
    }

    @Test
    @DisplayName("본문을 되살릴 수 없고 paymentId 도 없으면 묻지 못한다")
    void recheck_noPaymentIdAndNoCredential_cannotAsk() {
        // given. 청구 직전에 죽어 credentialId 가 안 적힌 행
        repository.claimed = List.of(
                new PaymentRecheck("checkout-1", "merchant-a", null, null, JWS, 92000L, "KRW", 1));

        // when
        worker().recheck();

        // then
        assertTrue(client.calls.isEmpty(), "Pay 를 부르지 않는다");
        assertEquals(List.of("schedule:checkout-1@" + NOW_SEOUL.plusSeconds(30)), repository.calls);
    }

    @Test
    @DisplayName("이미 누가 확정한 행이면 조용히 넘어간다")
    void recheck_rowAlreadyResolved_doesNothingMore() {
        // given
        repository.claimed = List.of(row("pay-1", 1));
        repository.resolveResult = false;
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-1", "pg-1", null, null);

        // when & then. 예외 없이 끝난다
        worker().recheck();
        assertEquals(List.of("resolve:checkout-1:SUCCESS:pay-1"), repository.calls);
    }

    @Test
    @DisplayName("한 건이 예외로 끝나도 나머지를 계속 확인한다")
    void recheck_oneRowThrows_continuesWithRemaining() {
        // given
        repository.claimed = List.of(
                new PaymentRecheck("checkout-boom", "merchant-a", "pay-boom", "cred-1", JWS, 92000L, "KRW", 1),
                row("pay-2", 1));
        client.failOn = "pay-boom";
        client.result = new PaymentResult(PaymentStatus.SUCCESS, "pay-2", "pg-2", null, null);

        // when
        worker().recheck();

        // then
        assertEquals(List.of("lookup:pay-boom", "lookup:pay-2"), client.calls);
        assertEquals(List.of("resolve:checkout-1:SUCCESS:pay-2"), repository.calls);
    }

    @Test
    @DisplayName("집을 것이 없으면 Pay 를 부르지 않는다")
    void recheck_nothingClaimed_doesNotCallPay() {
        // when
        worker().recheck();

        // then
        assertTrue(client.calls.isEmpty());
        assertTrue(repository.calls.isEmpty());
    }

    @Test
    @DisplayName("배치 크기와 리스가 0 이하면 기동에서 막는다")
    void constructor_nonPositiveBatchSizeOrLease_rejected() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> new PaymentRecheckWorker(repository, client, listener,
                MerchantPropertiesFixture.defaults(), payProperties(), Clock.fixed(NOW, SEOUL),
                0, Duration.ofMinutes(2)));
        assertThrows(IllegalArgumentException.class, () -> new PaymentRecheckWorker(repository, client, listener,
                MerchantPropertiesFixture.defaults(), payProperties(), Clock.fixed(NOW, SEOUL),
                100, Duration.ZERO));
    }

    @Test
    @DisplayName("집을 때 리스는 지금부터다")
    void recheck_claim_leaseIsRelativeToScanTime() {
        // when
        worker().recheck();

        // then
        assertEquals(NOW_SEOUL, repository.claimNow);
        assertEquals(NOW_SEOUL.plusMinutes(2), repository.claimLeaseUntil);
        assertEquals(MAX_ATTEMPTS, repository.claimMaxAttempts);
    }

    /** 집어 줄 행을 테스트가 심고 워커가 무엇을 기록하는지만 남긴다. */
    private static final class RecheckRepository implements CheckoutRepository {

        final List<String> calls = new ArrayList<>();
        List<PaymentRecheck> claimed = List.of();
        boolean resolveResult = true;
        OffsetDateTime claimNow;
        OffsetDateTime claimLeaseUntil;
        int claimMaxAttempts;

        @Override
        public List<PaymentRecheck> claimUnknownDue(OffsetDateTime now, OffsetDateTime leaseUntil,
                int maxAttempts, int limit) {
            claimNow = now;
            claimLeaseUntil = leaseUntil;
            claimMaxAttempts = maxAttempts;
            return claimed;
        }

        @Override
        public boolean resolvePayment(String id, PaymentStatus status, String payPaymentId) {
            calls.add("resolve:" + id + ":" + status + ":" + payPaymentId);
            return resolveResult;
        }

        @Override
        public boolean scheduleNextRecheck(String id, OffsetDateTime nextAttemptAt) {
            calls.add("schedule:" + id + "@" + nextAttemptAt);
            return true;
        }

        @Override
        public boolean insert(CheckoutSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CheckoutSession> findById(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean update(CheckoutSession session, long expectedVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int expireReady(OffsetDateTime now, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean beginComplete(String id, OffsetDateTime now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean finishComplete(String id, String completionResponseJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean revertComplete(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean beginPayment(String id, OffsetDateTime requestedAt, String credentialId,
                OffsetDateTime firstRecheckAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean finishPayment(String id, PaymentStatus status, String payPaymentId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecheckPayClient implements PayClient {

        final List<String> calls = new ArrayList<>();
        final List<PaymentRequest> requests = new ArrayList<>();
        PaymentResult result = PaymentResult.unknown("PAY_UNREACHABLE");
        String failOn;

        @Override
        public PaymentResult charge(PaymentRequest request) {
            calls.add("charge:" + request.businessCheckoutId());
            requests.add(request);
            return result;
        }

        @Override
        public PaymentResult lookup(String merchantId, String paymentId) {
            calls.add("lookup:" + paymentId);
            if (paymentId.equals(failOn)) {
                throw new IllegalStateException("클라이언트가 약속을 깼다");
            }
            return result;
        }
    }

    @Test
    @DisplayName("PaymentResult 의 paymentId 가 없으면 기존 값을 지우지 않게 null 을 넘긴다")
    void recheck_resolvedWithoutPaymentId_passesNull() {
        // given
        repository.claimed = List.of(row("pay-1", 1));
        client.result = new PaymentResult(PaymentStatus.FAILED, null, null, "DECLINED", null);

        // when
        worker().recheck();

        // then
        assertEquals(List.of("resolve:checkout-1:FAILED:null"), repository.calls);
        assertNull(client.result.paymentId());
    }
}
