package com.copenhagen.merchantmock.checkout;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcCheckoutRepository 의 SQL 검증(Jira 294). 인메모리 저장소가 흉내 내던 것을 진짜 DB 로 확인한다.
 *
 * <p>{@link InMemoryCheckoutRepository} 는 조건부 UPDATE 를 자바로 흉내 낸다. 그것이 통과한다고
 * SQL 이 맞는 것은 아니다. 특히 182 의 {@code expireReady} 는 서브쿼리에 ORDER BY 와 LIMIT 이 들어가
 * 자바 필터와 모양이 전혀 다르다.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcCheckoutRepositoryPostgresTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 14, 13, 0, 0, 0, ZoneOffset.ofHours(9));

    private EmbeddedPostgresSupport postgres;
    private JdbcCheckoutRepository repository;
    private JdbcTemplate jdbc;

    @BeforeAll
    void startPostgres() throws Exception {
        this.postgres = EmbeddedPostgresSupport.start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, null).migrate();
        this.jdbc = postgres.jdbcFor(dataSource);
        this.repository = new JdbcCheckoutRepository(jdbc);
    }

    @Test
    @DisplayName("넣고 다시 읽으면 값이 그대로다")
    void insertAndFindById_newSession_roundTripsAllColumns() {
        // given
        CheckoutSession session = ready("checkout-1", NOW.plusMinutes(5));

        // when
        boolean inserted = repository.insert(session);

        // then
        assertTrue(inserted);
        CheckoutSession stored = repository.findById("checkout-1").orElseThrow();
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, stored.status());
        assertEquals("KRW", stored.currency());
        assertEquals(92000L, stored.totalAmount());
        assertEquals(session.canonicalCheckout(), stored.canonicalCheckout());
        assertEquals(0L, stored.version());
        assertEquals(NOW.plusMinutes(5).toInstant(), stored.expiresAt().toInstant());
    }

    @Test
    @DisplayName("jsonb 컬럼이 문자열로 왕복한다")
    void insertAndFindById_jsonColumns_roundTripAsText() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        CheckoutSession stored = repository.findById("checkout-1").orElseThrow();

        // then
        assertTrue(stored.fulfillmentAddressJson().contains("홍길동"));
        assertTrue(stored.messagesJson().contains("ADDRESS_REQUIRED"));
    }

    @Test
    @DisplayName("같은 id 를 다시 넣으면 false 다")
    void insert_duplicateId_returnsFalse() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        boolean second = repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // then
        assertFalse(second);
        assertEquals(1, count());
    }

    @Test
    @DisplayName("없는 id 조회는 빈 값이다")
    void findById_unknownId_returnsEmpty() {
        // when & then
        assertTrue(repository.findById("nope").isEmpty());
    }

    @Test
    @DisplayName("버전이 맞아야 수정되고 버전이 오른다")
    void update_matchingVersion_appliesAndBumpsVersion() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        boolean updated = repository.update(ready("checkout-1", NOW.plusMinutes(10)), 0L);

        // then
        assertTrue(updated);
        assertEquals(1L, repository.findById("checkout-1").orElseThrow().version());
    }

    @Test
    @DisplayName("버전이 어긋나면 아무것도 바뀌지 않는다")
    void update_staleVersion_changesNothing() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        repository.update(ready("checkout-1", NOW.plusMinutes(10)), 0L);

        // when
        boolean updated = repository.update(ready("checkout-1", NOW.plusMinutes(30)), 0L);

        // then
        assertFalse(updated);
        assertEquals(1L, repository.findById("checkout-1").orElseThrow().version());
    }

    @Test
    @DisplayName("취소는 세 상태에서만 되고 나머지는 false 다")
    void cancel_byStatus_onlyCancelableStatesSucceed() {
        // given
        List<CheckoutStatus> cancelable =
                List.of(CheckoutStatus.INCOMPLETE, CheckoutStatus.REQUIRES_ESCALATION,
                        CheckoutStatus.READY_FOR_COMPLETE);
        List<CheckoutStatus> rejected =
                List.of(CheckoutStatus.COMPLETE_IN_PROGRESS, CheckoutStatus.COMPLETED, CheckoutStatus.CANCELED);

        // when & then
        for (CheckoutStatus status : cancelable) {
            String id = "ok-" + status;
            repository.insert(withStatus(id, status));
            assertTrue(repository.cancel(id), status + " 는 취소할 수 있어야 한다");
            assertEquals(CheckoutStatus.CANCELED, repository.findById(id).orElseThrow().status());
        }
        for (CheckoutStatus status : rejected) {
            String id = "no-" + status;
            repository.insert(withStatus(id, status));
            assertFalse(repository.cancel(id), status + " 는 취소되면 안 된다");
            assertEquals(status, repository.findById(id).orElseThrow().status());
        }
    }

    @Test
    @DisplayName("만료 스윕이 시한 지난 ready_for_complete 만 닫는다")
    void expireReady_mixedRows_closesOnlyExpiredReadyCheckouts() {
        // given
        // 대상이 아닌 상태에도 지난 expires_at 을 넣는다. 그래야 시각 조건이 아니라 status 조건이 시험된다.
        repository.insert(ready("expired", NOW.minusMinutes(1)));
        repository.insert(ready("alive", NOW.plusMinutes(5)));
        repository.insert(expiredWithStatus("incomplete", CheckoutStatus.INCOMPLETE));
        repository.insert(expiredWithStatus("escalation", CheckoutStatus.REQUIRES_ESCALATION));
        repository.insert(expiredWithStatus("in-progress", CheckoutStatus.COMPLETE_IN_PROGRESS));
        repository.insert(expiredWithStatus("completed", CheckoutStatus.COMPLETED));
        repository.insert(expiredWithStatus("canceled", CheckoutStatus.CANCELED));

        // when
        int closed = repository.expireReady(NOW, 100);

        // then
        assertEquals(1, closed);
        assertEquals(CheckoutStatus.CANCELED, repository.findById("expired").orElseThrow().status());
        assertEquals(1L, repository.findById("expired").orElseThrow().version());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("alive").orElseThrow().status());
        assertEquals(CheckoutStatus.INCOMPLETE, repository.findById("incomplete").orElseThrow().status());
        assertEquals(CheckoutStatus.REQUIRES_ESCALATION, repository.findById("escalation").orElseThrow().status());
        assertEquals(CheckoutStatus.COMPLETE_IN_PROGRESS,
                repository.findById("in-progress").orElseThrow().status());
        assertEquals(CheckoutStatus.COMPLETED, repository.findById("completed").orElseThrow().status());
        assertEquals(0L, repository.findById("canceled").orElseThrow().version(), "이미 취소된 건은 다시 안 센다");
    }

    @Test
    @DisplayName("만료 스윕이 limit 만큼만 닫고 오래된 것부터 집는다")
    void expireReady_moreThanLimit_closesOldestFirst() {
        // given
        // 삽입 순서와 만료 순서를 일부러 어긋나게 둔다. ORDER BY 가 빠지면 물리 순서로 집혀 실패한다.
        repository.insert(ready("newest", NOW.minusMinutes(1)));
        repository.insert(ready("second", NOW.minusMinutes(10)));
        repository.insert(ready("third", NOW.minusMinutes(20)));
        repository.insert(ready("oldest", NOW.minusMinutes(30)));

        // when
        int closed = repository.expireReady(NOW, 2);

        // then
        assertEquals(2, closed);
        assertEquals(CheckoutStatus.CANCELED, repository.findById("oldest").orElseThrow().status());
        assertEquals(CheckoutStatus.CANCELED, repository.findById("third").orElseThrow().status());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("second").orElseThrow().status());
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("newest").orElseThrow().status());
    }

    @Test
    @DisplayName("만료 스윕은 expires_at 이 없는 행을 건드리지 않는다")
    void expireReady_nullExpiresAt_leavesRowUntouched() {
        // given
        repository.insert(ready("no-expiry", null));

        // when
        int closed = repository.expireReady(NOW, 100);

        // then
        assertEquals(0, closed);
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("no-expiry").orElseThrow().status());
    }

    @Test
    @DisplayName("만료 스윕이 쓰는 인덱스를 실행 계획이 탄다")
    void expireReady_query_usesExpiryIndex() {
        // given
        jdbc.execute("SET enable_seqscan = off");

        // when
        List<String> plan = jdbc.queryForList("""
                EXPLAIN SELECT id FROM checkout_sessions
                 WHERE status = 'READY_FOR_COMPLETE' AND expires_at < ?
                 ORDER BY expires_at LIMIT 100
                """, String.class, NOW);

        // then
        String rendered = String.join(System.lineSeparator(), plan);
        assertTrue(rendered.contains("ix_checkout_expiry"), rendered);
    }

    @Test
    @DisplayName("동시에 취소하면 한 요청만 행을 바꾼다")
    void cancel_concurrentRequests_onlyOneWins() throws Exception {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Boolean>> tasks = IntStream.range(0, threads)
                .mapToObj(i -> (Callable<Boolean>) () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return repository.cancel("checkout-1");
                })
                .toList();

        // when
        List<Future<Boolean>> results = pool.invokeAll(tasks);
        pool.shutdown();

        // then
        int winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                winners++;
            }
        }
        assertEquals(1, winners, "조건부 UPDATE 라 한 요청만 1행을 바꿔야 한다");
        assertEquals(1L, repository.findById("checkout-1").orElseThrow().version());
    }

    @Test
    @DisplayName("서명과 결제·완료 컬럼이 그대로 읽힌다")
    void insertAndFindById_signedSession_roundTripsSignatureAndPaymentColumns() {
        // given
        CheckoutSession signed = ready("checkout-1", NOW.plusMinutes(5))
                .withMerchantAuthorizationJws("eyJhbGciOiJFUzI1NiJ9.eyJjaGVja291dElkIjoiY2hlY2tvdXQtMSJ9.sig");
        repository.insert(signed);

        // when
        CheckoutSession stored = repository.findById("checkout-1").orElseThrow();

        // then
        assertEquals(signed.merchantAuthorizationJws(), stored.merchantAuthorizationJws());
        assertNull(stored.completionResponseJson());
        assertNull(stored.paymentStatus());
        assertNull(stored.payPaymentId());

        repository.beginPayment("checkout-1", NOW, "cred-1", NOW.plusSeconds(30));
        repository.finishPayment("checkout-1", PaymentStatus.SUCCESS, "pay-1");
        CheckoutSession paid = repository.findById("checkout-1").orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, paid.paymentStatus());
        assertEquals("pay-1", paid.payPaymentId());
    }

    @Test
    @DisplayName("PUT 재계산이 서명 컬럼을 새 값으로 바꾸고 null 이면 비운다")
    void update_resigned_replacesSignatureColumn() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)).withMerchantAuthorizationJws("a.b.c"));

        // when
        repository.update(ready("checkout-1", NOW.plusMinutes(10)).withMerchantAuthorizationJws("d.e.f"), 0L);
        assertEquals("d.e.f", repository.findById("checkout-1").orElseThrow().merchantAuthorizationJws());
        repository.update(ready("checkout-1", null), 1L);

        // then
        assertNull(repository.findById("checkout-1").orElseThrow().merchantAuthorizationJws(),
                "incomplete 로 내려가면 서명을 폐기한다");
    }

    @Test
    @DisplayName("Complete 시작은 만료 전 ready_for_complete 에만 되고 두 번째는 false 다")
    void beginComplete_readyBeforeExpiry_marksInProgressOnce() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        boolean first = repository.beginComplete("checkout-1", NOW);
        boolean second = repository.beginComplete("checkout-1", NOW);

        // then
        assertTrue(first);
        assertFalse(second);
        assertEquals(CheckoutStatus.COMPLETE_IN_PROGRESS, repository.findById("checkout-1").orElseThrow().status());
    }

    @Test
    @DisplayName("Complete 시작은 expires_at 이 지났으면 false 다. 만료 검사의 1차 방어선")
    void beginComplete_expired_returnsFalse() {
        // given
        repository.insert(ready("checkout-1", NOW.minusSeconds(1)));
        repository.insert(ready("checkout-2", NOW));

        // when & then
        assertFalse(repository.beginComplete("checkout-1", NOW));
        assertFalse(repository.beginComplete("checkout-2", NOW), "같은 초는 만료다");
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("checkout-1").orElseThrow().status());
    }

    @Test
    @DisplayName("Complete 시작은 다른 상태에서는 false 다")
    void beginComplete_otherStatuses_returnFalse() {
        for (CheckoutStatus status : CheckoutStatus.values()) {
            if (status == CheckoutStatus.READY_FOR_COMPLETE) {
                continue;
            }
            String id = "checkout-" + status.name().toLowerCase();
            repository.insert(new CheckoutSession(id, status, "KRW", 92000L, null, null, null,
                    "{\"id\":\"" + id + "\"}", NOW.plusMinutes(5), 0L));
            assertFalse(repository.beginComplete(id, NOW), status.name());
        }
    }

    @Test
    @DisplayName("Complete 종료는 진행 중 행만 completed 로 바꾸고 응답을 jsonb 로 저장한다")
    void finishComplete_inProgress_completesAndStoresResponseOnce() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        repository.beginComplete("checkout-1", NOW);
        String response = "{\"id\":\"checkout-1\",\"status\":\"completed\",\"order\":{\"id\":\"order-1\"}}";

        // when
        boolean finished = repository.finishComplete("checkout-1", response);
        boolean again = repository.finishComplete("checkout-1", "{\"id\":\"other\"}");

        // then
        assertTrue(finished);
        assertFalse(again);
        CheckoutSession stored = repository.findById("checkout-1").orElseThrow();
        assertEquals(CheckoutStatus.COMPLETED, stored.status());
        assertTrue(stored.completionResponseJson().contains("\"order\""));
        assertTrue(stored.completionResponseJson().contains("\"order-1\""), "처음 저장한 응답이 남는다");
    }

    @Test
    @DisplayName("Complete 되돌림은 진행 중 행만 ready_for_complete 로 바꾼다")
    void revertComplete_inProgress_revertsOnce() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        assertFalse(repository.revertComplete("checkout-1"), "진행 중이 아니면 false");
        repository.beginComplete("checkout-1", NOW);

        // when
        boolean reverted = repository.revertComplete("checkout-1");

        // then
        assertTrue(reverted);
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, repository.findById("checkout-1").orElseThrow().status());
        assertTrue(repository.beginComplete("checkout-1", NOW), "되돌린 뒤 다시 Complete 할 수 있다");
    }

    @Test
    @DisplayName("동시에 Complete 를 시작하면 한 요청만 행을 바꾼다")
    void beginComplete_concurrentRequests_onlyOneWins() throws Exception {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Boolean>> tasks = IntStream.range(0, threads)
                .mapToObj(i -> (Callable<Boolean>) () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return repository.beginComplete("checkout-1", NOW);
                })
                .toList();

        // when
        List<Future<Boolean>> results = pool.invokeAll(tasks);
        pool.shutdown();

        // then
        int winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                winners++;
            }
        }
        assertEquals(1, winners, "같은 키로 동시에 두 번 와도 Pay 는 한 번이다(Jira 176 완료 조건)");
    }

    @Test
    @DisplayName("청구 직전 표시는 FAILED 로 끝난 행에도 된다. 재승인 재청구 경로다(8.1절)")
    void beginPayment_afterFailed_marksProcessingAgain() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        repository.beginPayment("checkout-1", NOW, "cred-1", NOW.plusSeconds(30));
        repository.finishPayment("checkout-1", PaymentStatus.FAILED, null);

        // when
        boolean again = repository.beginPayment("checkout-1", NOW.plusMinutes(1), "cred-2", NOW.plusSeconds(90));

        // then
        assertTrue(again);
        assertEquals("PROCESSING", paymentStatus("checkout-1"));
        assertEquals("cred-2", jdbc.queryForObject(
                "SELECT payment_credential_id FROM checkout_sessions WHERE id = ?", String.class, "checkout-1"));
    }

    @Test
    @DisplayName("청구 직전 표시는 SUCCESS·UNKNOWN·PROCESSING 행에는 안 된다")
    void beginPayment_settledOrPending_returnsFalse() {
        for (PaymentStatus status : List.of(PaymentStatus.SUCCESS, PaymentStatus.UNKNOWN, PaymentStatus.PROCESSING)) {
            String id = "checkout-" + status.name().toLowerCase();
            repository.insert(ready(id, NOW.plusMinutes(5)));
            repository.beginPayment(id, NOW, "cred-1", NOW.plusSeconds(30));
            if (status != PaymentStatus.PROCESSING) {
                repository.finishPayment(id, status, "pay-1");
            }
            assertFalse(repository.beginPayment(id, NOW, "cred-2", NOW.plusSeconds(30)), status.name());
        }
    }

    @Test
    @DisplayName("청구 직전 표시는 결제가 없는 행에만 되고 두 번째는 false 다")
    void beginPayment_freshRow_marksProcessingOnce() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        boolean first = repository.beginPayment("checkout-1", NOW, "cred-1", NOW.plusSeconds(30));
        boolean second = repository.beginPayment("checkout-1", NOW.plusSeconds(1), "cred-1", NOW.plusSeconds(31));

        // then
        assertTrue(first);
        assertFalse(second, "이미 결제가 시작된 행은 다시 집지 않는다");
        assertEquals("PROCESSING", paymentStatus("checkout-1"));
        assertEquals(NOW.toInstant(), jdbc.queryForObject(
                "SELECT payment_requested_at FROM checkout_sessions WHERE id = ?",
                OffsetDateTime.class, "checkout-1").toInstant());
        assertEquals(0, jdbc.queryForObject(
                "SELECT payment_attempt_count FROM checkout_sessions WHERE id = ?", Integer.class, "checkout-1"),
                "attempt_count 는 재조회 횟수라 청구가 올리지 않는다");
        assertEquals(1L, repository.findById("checkout-1").orElseThrow().version());
    }

    @Test
    @DisplayName("결과 반영은 PROCESSING 행에만 되고 paymentId 를 저장한다")
    void finishPayment_processingRow_appliesStatusAndPaymentIdOnce() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        repository.beginPayment("checkout-1", NOW, "cred-1", NOW.plusSeconds(30));

        // when
        boolean finished = repository.finishPayment("checkout-1", PaymentStatus.SUCCESS, "pay-1");
        boolean again = repository.finishPayment("checkout-1", PaymentStatus.FAILED, "pay-2");

        // then
        assertTrue(finished);
        assertFalse(again, "이미 끝난 결제는 다시 바꾸지 않는다");
        assertEquals("SUCCESS", paymentStatus("checkout-1"));
        assertEquals("pay-1", payPaymentId("checkout-1"));
        assertEquals(2L, repository.findById("checkout-1").orElseThrow().version());
    }

    @Test
    @DisplayName("결과 반영에 paymentId 가 없으면 있던 값을 지우지 않는다")
    void finishPayment_nullPaymentId_keepsExistingPaymentId() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        repository.beginPayment("checkout-1", NOW, "cred-1", NOW.plusSeconds(30));
        jdbc.update("UPDATE checkout_sessions SET pay_payment_id = 'pay-before' WHERE id = ?", "checkout-1");

        // when
        boolean finished = repository.finishPayment("checkout-1", PaymentStatus.UNKNOWN, null);

        // then
        assertTrue(finished);
        assertEquals("UNKNOWN", paymentStatus("checkout-1"));
        assertEquals("pay-before", payPaymentId("checkout-1"));
    }

    @Test
    @DisplayName("결제가 시작되지 않은 행에 결과를 반영하면 false 다")
    void finishPayment_noPaymentStarted_returnsFalse() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        boolean finished = repository.finishPayment("checkout-1", PaymentStatus.SUCCESS, "pay-1");

        // then
        assertFalse(finished);
        assertNull(paymentStatus("checkout-1"));
        assertNull(payPaymentId("checkout-1"));
    }

    @Test
    @DisplayName("동시에 청구 표시를 하면 한 요청만 행을 바꾼다")
    void beginPayment_concurrentRequests_onlyOneWins() throws Exception {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Boolean>> tasks = IntStream.range(0, threads)
                .mapToObj(i -> (Callable<Boolean>) () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return repository.beginPayment("checkout-1", NOW, "cred-1", NOW.plusSeconds(30));
                })
                .toList();

        // when
        List<Future<Boolean>> results = pool.invokeAll(tasks);
        pool.shutdown();

        // then
        int winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                winners++;
            }
        }
        assertEquals(1, winners, "조건부 UPDATE 라 한 요청만 PROCESSING 을 찍어야 한다");
        assertEquals(1L, repository.findById("checkout-1").orElseThrow().version());
    }

    private String paymentStatus(String id) {
        return jdbc.queryForObject("SELECT payment_status FROM checkout_sessions WHERE id = ?", String.class, id);
    }

    private String payPaymentId(String id) {
        return jdbc.queryForObject("SELECT pay_payment_id FROM checkout_sessions WHERE id = ?", String.class, id);
    }

    private static CheckoutSession ready(String id, OffsetDateTime expiresAt) {
        return new CheckoutSession(
                id,
                CheckoutStatus.READY_FOR_COMPLETE,
                "KRW",
                92000L,
                "{\"recipient\":\"홍길동\",\"address_line1\":\"서울특별시 강남구 테헤란로 1\"}",
                "[{\"code\":\"ADDRESS_REQUIRED\",\"severity\":\"error\"}]",
                "{\"line-1\":\"product-101-260\"}",
                "{\"id\":\"" + id + "\",\"status\":\"ready_for_complete\"}",
                expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.SECONDS),
                0L);
    }

    /** 시한이 지난 채로 다른 상태에 있는 행. status 조건을 시험하는 데 쓴다. */
    private static CheckoutSession expiredWithStatus(String id, CheckoutStatus status) {
        CheckoutSession base = ready(id, NOW.minusMinutes(5));
        return new CheckoutSession(base.id(), status, base.currency(), base.totalAmount(),
                base.fulfillmentAddressJson(), base.messagesJson(), base.lineSkusJson(),
                base.canonicalCheckout(), base.expiresAt(), base.version());
    }

    private static CheckoutSession withStatus(String id, CheckoutStatus status) {
        CheckoutSession base = ready(id, null);
        return new CheckoutSession(base.id(), status, base.currency(), base.totalAmount(),
                base.fulfillmentAddressJson(), base.messagesJson(), base.lineSkusJson(),
                base.canonicalCheckout(), base.expiresAt(), base.version());
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM checkout_sessions", Integer.class);
    }
    /** UNKNOWN 으로 남은 행 하나. 청구까지 갔다가 결과를 못 받은 상태를 만든다. */
    private void unknownRow(String id, int attemptCount, OffsetDateTime nextAttemptAt, String payPaymentId) {
        repository.insert(ready(id, NOW.plusMinutes(5)));
        repository.beginPayment(id, NOW, "cred-" + id, NOW.plusSeconds(30));
        repository.finishPayment(id, PaymentStatus.UNKNOWN, payPaymentId);
        jdbc.update("""
                UPDATE checkout_sessions
                   SET payment_attempt_count = ?, payment_next_attempt_at = ?
                 WHERE id = ?
                """, attemptCount, nextAttemptAt, id);
    }

    @Test
    @DisplayName("청구 직전에 credentialId 와 첫 재조회 시각을 같이 적는다")
    void beginPayment_freshRow_storesCredentialAndFirstRecheck() {
        // given
        repository.insert(ready("checkout-1", NOW.plusMinutes(5)));

        // when
        repository.beginPayment("checkout-1", NOW, "cred-1", NOW.plusSeconds(30));

        // then
        assertEquals("cred-1", jdbc.queryForObject(
                "SELECT payment_credential_id FROM checkout_sessions WHERE id = ?", String.class, "checkout-1"));
        assertEquals(NOW.plusSeconds(30).toInstant(), jdbc.queryForObject(
                "SELECT payment_next_attempt_at FROM checkout_sessions WHERE id = ?",
                OffsetDateTime.class, "checkout-1").toInstant());
    }

    @Test
    @DisplayName("기한이 된 UNKNOWN 을 집으면서 리스를 걸고 시도 횟수를 올린다")
    void claimUnknownDue_dueRow_returnsProjectionAndLeases() {
        // given
        unknownRow("checkout-1", 1, NOW, "pay-1");

        // when
        OffsetDateTime leaseUntil = NOW.plusMinutes(2);
        List<PaymentRecheck> claimed = repository.claimUnknownDue(NOW, leaseUntil, 10, 100);

        // then
        assertEquals(1, claimed.size());
        PaymentRecheck row = claimed.get(0);
        assertEquals("checkout-1", row.checkoutId());
        assertEquals("pay-1", row.payPaymentId());
        assertEquals("cred-checkout-1", row.credentialId());
        assertEquals(2, row.attemptCount(), "집으면서 올린 뒤의 값이다");
        assertEquals(leaseUntil.toInstant(), jdbc.queryForObject(
                "SELECT payment_next_attempt_at FROM checkout_sessions WHERE id = ?",
                OffsetDateTime.class, "checkout-1").toInstant());
    }

    @Test
    @DisplayName("리스 때문에 연속으로 두 번 부르면 둘째는 비어 나온다")
    void claimUnknownDue_calledTwiceInARow_secondCallClaimsNothing() {
        // given
        unknownRow("checkout-1", 1, NOW, "pay-1");

        // when
        List<PaymentRecheck> first = repository.claimUnknownDue(NOW, NOW.plusMinutes(2), 10, 100);
        List<PaymentRecheck> second = repository.claimUnknownDue(NOW.plusSeconds(1), NOW.plusMinutes(2), 10, 100);

        // then
        assertEquals(1, first.size());
        assertTrue(second.isEmpty());
    }

    @Test
    @DisplayName("기한 전, 한도 초과, UNKNOWN 이 아닌 행은 집지 않는다")
    void claimUnknownDue_notDueOrExhaustedOrResolved_notClaimed() {
        // given
        unknownRow("checkout-later", 1, NOW.plusMinutes(5), "pay-1");
        unknownRow("checkout-exhausted", 10, NOW, "pay-2");
        repository.insert(ready("checkout-done", NOW.plusMinutes(5)));
        repository.beginPayment("checkout-done", NOW, "cred-3", NOW.minusSeconds(1));
        repository.finishPayment("checkout-done", PaymentStatus.SUCCESS, "pay-3");

        // when
        List<PaymentRecheck> claimed = repository.claimUnknownDue(NOW, NOW.plusMinutes(2), 10, 100);

        // then
        assertTrue(claimed.isEmpty(), claimed.toString());
    }

    @Test
    @DisplayName("배치 크기를 지키고 기한이 이른 것부터 집는다")
    void claimUnknownDue_moreDueRowsThanLimit_claimsOldestFirst() {
        // given
        unknownRow("checkout-late", 1, NOW.minusSeconds(1), "pay-1");
        unknownRow("checkout-early", 1, NOW.minusMinutes(1), "pay-2");

        // when
        List<PaymentRecheck> claimed = repository.claimUnknownDue(NOW, NOW.plusMinutes(2), 10, 1);

        // then
        assertEquals(List.of("checkout-early"), claimed.stream().map(PaymentRecheck::checkoutId).toList());
    }

    @Test
    @DisplayName("확정은 UNKNOWN 행에만 되고 다음 시각을 비운다")
    void resolvePayment_unknownRow_closesOnceAndClearsNextAttempt() {
        // given
        unknownRow("checkout-1", 1, NOW, "pay-1");

        // when
        boolean resolved = repository.resolvePayment("checkout-1", PaymentStatus.SUCCESS, "pay-1");
        boolean again = repository.resolvePayment("checkout-1", PaymentStatus.FAILED, "pay-1");

        // then
        assertTrue(resolved);
        assertFalse(again, "이미 확정된 행은 다시 바꾸지 않는다");
        assertEquals("SUCCESS", paymentStatus("checkout-1"));
        assertNull(jdbc.queryForObject(
                "SELECT payment_next_attempt_at FROM checkout_sessions WHERE id = ?",
                OffsetDateTime.class, "checkout-1"));
    }

    @Test
    @DisplayName("확정에 paymentId 가 없으면 있던 값을 지우지 않는다")
    void resolvePayment_nullPaymentId_keepsExistingPaymentId() {
        // given
        unknownRow("checkout-1", 1, NOW, "pay-before");

        // when
        repository.resolvePayment("checkout-1", PaymentStatus.FAILED, null);

        // then
        assertEquals("pay-before", payPaymentId("checkout-1"));
    }

    @Test
    @DisplayName("다음 확인 시각 밀기는 UNKNOWN 행에만 된다")
    void scheduleNextRecheck_unknownRowOnly() {
        // given
        unknownRow("checkout-1", 1, NOW, "pay-1");
        repository.insert(ready("checkout-2", NOW.plusMinutes(5)));

        // when
        boolean pushed = repository.scheduleNextRecheck("checkout-1", NOW.plusMinutes(1));
        boolean noRow = repository.scheduleNextRecheck("checkout-2", NOW.plusMinutes(1));

        // then
        assertTrue(pushed);
        assertFalse(noRow, "결제가 시작되지도 않은 행은 밀 것이 없다");
        assertEquals(NOW.plusMinutes(1).toInstant(), jdbc.queryForObject(
                "SELECT payment_next_attempt_at FROM checkout_sessions WHERE id = ?",
                OffsetDateTime.class, "checkout-1").toInstant());
    }

    @Test
    @DisplayName("재조회 워커가 쓰는 인덱스를 실행 계획이 탄다")
    void unknownScan_query_usesPaymentIndex() {
        // given
        jdbc.execute("SET enable_seqscan = off");

        // when
        List<String> plan = jdbc.queryForList("""
                EXPLAIN SELECT id FROM checkout_sessions
                 WHERE payment_status = 'UNKNOWN' AND payment_next_attempt_at <= ?
                 ORDER BY payment_next_attempt_at LIMIT 100
                """, String.class, NOW);

        // then
        assertTrue(String.join(System.lineSeparator(), plan).contains("ix_checkout_payment"), plan.toString());
    }
}
