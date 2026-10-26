package com.copenhagen.merchantmock.webhook;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.copenhagen.merchantmock.support.LogContext;
import org.slf4j.MDC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주문 사건 → 발송 대장 행 → 커밋 뒤 첫 발송(DESIGN.md 8.6절). 발송 자체는 가짜다.
 */
class OrderWebhookPublisherTest {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final List<String> dispatched = new ArrayList<>();
    private InMemoryWebhookRepository repository;
    private OrderWebhookPublisher publisher;

    @BeforeEach
    void setUp() {
        repository = new InMemoryWebhookRepository();
        WebhookDispatcher dispatcher = webhookId -> {
            dispatched.add(webhookId);
            return WebhookDispatcher.Result.DELIVERED;
        };
        publisher = new OrderWebhookPublisher(repository, dispatcher, Runnable::run, objectMapper,
                MerchantPropertiesFixture.defaults(),
                Clock.fixed(WebhookTestSupport.NOW, WebhookTestSupport.SEOUL));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("주문 생성 사건은 order.created 행 하나를 만들고 Core 확정본 5.2 모양의 페이로드를 담는다")
    void orderCreated_anyOrder_insertsPendingRowWithWrappedPayload() {
        // when
        publisher.orderCreated(WebhookTestSupport.order());

        // then
        List<WebhookDelivery> rows = repository.findByOrderId(WebhookTestSupport.ORDER_ID);
        assertEquals(1, rows.size());
        WebhookDelivery row = rows.get(0);
        assertEquals(WebhookEventType.ORDER_CREATED, row.eventType());
        assertEquals(WebhookStatus.PENDING, row.status());
        assertEquals(0, row.attemptCount());
        assertNull(row.signed(), "아직 보내지 않았으니 서명 기록이 없다");

        Map<String, Object> payload = objectMapper.readValue(row.payloadJson(), JSON_OBJECT);
        // 393: 어느 몰의 주문인지 merchant_id 가 order 옆에 붙는다.
        assertEquals(2, payload.size(), "최상위는 merchant_id 와 order 둘이다");
        assertEquals("merchant-a", payload.get("merchant_id"));
        Map<String, Object> order = objectMapper.readValue(
                objectMapper.writeValueAsString(payload.get("order")), JSON_OBJECT);
        assertEquals(WebhookTestSupport.ORDER_ID, order.get("id"));
        assertEquals(WebhookTestSupport.CHECKOUT_ID, order.get("checkout_id"));
        assertEquals("CREATED", order.get("status"));
        assertEquals("2026-09-16T13:00:00+09:00", order.get("updated_at"));
        assertEquals(List.of("id", "checkout_id", "status", "fulfillment", "updated_at"),
                List.copyOf(order.keySet()), "주문 조회 응답과 같은 다섯 필드다");
    }

    @Test
    @DisplayName("트랜잭션이 없으면 바로 발송한다")
    void orderCreated_withoutTransaction_dispatchesImmediately() {
        // when
        publisher.orderCreated(WebhookTestSupport.order());

        // then
        assertEquals(1, dispatched.size());
        assertEquals(repository.findByOrderId(WebhookTestSupport.ORDER_ID).get(0).webhookId(), dispatched.get(0));
    }

    @Test
    @DisplayName("트랜잭션 안에서는 커밋 뒤에야 발송한다")
    void orderCreated_insideTransaction_dispatchesAfterCommitOnly() {
        // given
        TransactionSynchronizationManager.initSynchronization();

        // when
        publisher.orderCreated(WebhookTestSupport.order());

        // then
        assertEquals(1, repository.size(), "행은 트랜잭션 안에서 바로 남는다");
        assertTrue(dispatched.isEmpty(), "커밋 전에는 HTTP 가 나가면 안 된다");

        // when: 커밋
        List<TransactionSynchronization> synchronizations =
                List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        // then
        assertEquals(1, dispatched.size());
    }

    @Test
    @DisplayName("상태 변경 사건은 order.updated 다")
    void orderUpdated_anyOrder_insertsUpdatedRow() {
        // when
        publisher.orderUpdated(WebhookTestSupport.order());

        // then
        List<WebhookDelivery> rows = repository.findByOrderId(WebhookTestSupport.ORDER_ID);
        assertEquals(1, rows.size());
        assertEquals(WebhookEventType.ORDER_UPDATED, rows.get(0).eventType());
    }

    @Test
    @DisplayName("사건마다 webhook_id 가 다르다")
    void enqueue_twoEvents_haveDistinctWebhookIds() {
        // when
        publisher.orderCreated(WebhookTestSupport.order());
        publisher.orderUpdated(WebhookTestSupport.order());

        // then
        List<WebhookDelivery> rows = repository.findByOrderId(WebhookTestSupport.ORDER_ID);
        assertEquals(2, rows.size());
        assertTrue(!rows.get(0).webhookId().equals(rows.get(1).webhookId()));
    }

    @Test
    @DisplayName("새 행의 next_attempt_at 은 지금이 아니라 now + retry-initial-delay 다. 워커가 첫 발송과 겹치지 않게 한다")
    void orderCreated_newRow_nextAttemptIsPushedByFirstDelay() {
        // when
        publisher.orderCreated(WebhookTestSupport.order());

        // then
        WebhookDelivery row = repository.findByOrderId(WebhookTestSupport.ORDER_ID).get(0);
        assertEquals(WebhookTestSupport.NOW_SEOUL.plus(WebhookTestSupport.FIRST_DELAY), row.nextAttemptAt());
        assertEquals(WebhookTestSupport.NOW_SEOUL, row.createdAt());
    }

    @Test
    @DisplayName("커밋 뒤 발송 스레드가 요청의 requestId 를 이어받는다. 웹훅 로그가 체크아웃 로그와 한 줄로 묶인다")
    void orderCreated_asyncExecutor_carriesRequestIdIntoDispatch() throws Exception {
        // given
        MDC.put(LogContext.REQUEST_ID, "core-req-42");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> seen = new AtomicReference<>();
        WebhookDispatcher capturing = webhookId -> {
            seen.set(MDC.get(LogContext.REQUEST_ID));
            done.countDown();
            return WebhookDispatcher.Result.DELIVERED;
        };
        OrderWebhookPublisher async = new OrderWebhookPublisher(repository, capturing,
                command -> new Thread(command).start(), objectMapper, MerchantPropertiesFixture.defaults(),
                Clock.fixed(WebhookTestSupport.NOW, WebhookTestSupport.SEOUL));

        try {
            // when
            async.orderCreated(WebhookTestSupport.order());

            // then
            assertTrue(done.await(5, TimeUnit.SECONDS), "발송 스레드가 돌아야 한다");
            assertEquals("core-req-42", seen.get());
        } finally {
            MDC.clear();
        }
    }
}
