package com.copenhagen.merchantmock.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 웹훅 재전송 워커(Jira 184). 무엇을 언제 집는지만 본다. 발송과 결과 기록은 HttpWebhookDispatcherTest 몫이다.
 *
 * <p>행은 NOW 에 태어나고 next_attempt_at 은 NOW + 10초다(DESIGN.md 8.7절). 워커의 시계를 그보다 앞뒤로
 * 돌려 기한 도래를 만든다. 한도는 MerchantPropertiesFixture 기본값인 8회다.
 */
class WebhookRetryWorkerTest {

    private static final int MAX_ATTEMPTS = 8;

    private InMemoryWebhookRepository repository;
    private List<String> dispatched;

    @BeforeEach
    void setUp() {
        repository = new InMemoryWebhookRepository();
        dispatched = new ArrayList<>();
    }

    private WebhookRetryWorker workerAt(Duration afterNow, int batchSize) {
        return workerAt(afterNow, batchSize, webhookId -> {
            dispatched.add(webhookId);
            return WebhookDispatcher.Result.DELIVERED;
        });
    }

    private WebhookRetryWorker workerAt(Duration afterNow, int batchSize, WebhookDispatcher dispatcher) {
        Clock clock = Clock.fixed(WebhookTestSupport.NOW.plus(afterNow), WebhookTestSupport.SEOUL);
        return new WebhookRetryWorker(repository, dispatcher, MerchantPropertiesFixture.defaults(), clock,
                batchSize, Duration.ofMinutes(1));
    }

    /** {@code attemptCount} 번 실패해 다음 시도가 {@code NOW + dueAfter} 로 잡힌 행. */
    private WebhookDelivery pendingWith(String webhookId, int attemptCount, Duration dueAfter) {
        WebhookDelivery base = WebhookTestSupport.pendingCreated(webhookId);
        return new WebhookDelivery(base.webhookId(), base.orderId(), base.eventType(), base.payloadJson(),
                base.signed(), WebhookStatus.PENDING, attemptCount, WebhookTestSupport.NOW_SEOUL.plus(dueAfter),
                null, base.createdAt());
    }

    @Test
    @DisplayName("기한이 된 PENDING 을 집어 발송기에 넘긴다")
    void retry_duePendingRow_dispatchesIt() {
        // given
        repository.put(pendingWith("webhook-1", 1, Duration.ofSeconds(10)));

        // when
        workerAt(Duration.ofSeconds(30), 100).retry();

        // then
        assertEquals(List.of("webhook-1"), dispatched);
    }

    @Test
    @DisplayName("아직 기한이 안 된 행은 건드리지 않는다. 커밋 직후 첫 발송과 겹치지 않는 이유다")
    void retry_notYetDue_leavesRowUntouched() {
        // given
        repository.put(pendingWith("webhook-1", 0, Duration.ofSeconds(10)));

        // when
        workerAt(Duration.ofSeconds(5), 100).retry();

        // then
        assertTrue(dispatched.isEmpty());
        assertEquals(WebhookStatus.PENDING, repository.findById("webhook-1").orElseThrow().status());
    }

    @Test
    @DisplayName("집은 행의 next_attempt_at 을 리스만큼 밀어 다음 스캔이 같은 행을 다시 집지 않는다")
    void retry_claimedRow_leaseKeepsNextScanFromPickingItAgain() {
        // given. 발송기가 아무것도 기록하지 않는다. 리스가 없으면 다음 스캔이 같은 행을 다시 집는다
        repository.put(pendingWith("webhook-1", 1, Duration.ofSeconds(10)));
        WebhookDispatcher inert = webhookId -> {
            dispatched.add(webhookId);
            return WebhookDispatcher.Result.SKIPPED_NO_SIGNING_KEY;
        };

        // when
        workerAt(Duration.ofSeconds(30), 100, inert).retry();
        workerAt(Duration.ofSeconds(31), 100, inert).retry();

        // then
        assertEquals(List.of("webhook-1"), dispatched);
    }

    @Test
    @DisplayName("한도를 넘긴 PENDING 은 FAILED 로 닫고 집지 않는다")
    void retry_attemptsExhausted_closesRowAsFailed() {
        // given
        repository.put(pendingWith("webhook-1", MAX_ATTEMPTS, Duration.ofSeconds(10)));

        // when
        workerAt(Duration.ofSeconds(30), 100).retry();

        // then
        assertEquals(WebhookStatus.FAILED, repository.findById("webhook-1").orElseThrow().status());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    @DisplayName("한도 직전인 행은 아직 보낸다")
    void retry_oneAttemptLeft_stillDispatches() {
        // given
        repository.put(pendingWith("webhook-1", MAX_ATTEMPTS - 1, Duration.ofSeconds(10)));

        // when
        workerAt(Duration.ofSeconds(30), 100).retry();

        // then
        assertEquals(List.of("webhook-1"), dispatched);
        assertEquals(WebhookStatus.PENDING, repository.findById("webhook-1").orElseThrow().status());
    }

    @Test
    @DisplayName("DELIVERED 와 FAILED 는 끝이라 다시 보내지 않는다")
    void retry_terminalRows_neverDispatched() {
        // given
        WebhookDelivery delivered = WebhookTestSupport.pendingCreated("webhook-1");
        repository.put(new WebhookDelivery(delivered.webhookId(), delivered.orderId(), delivered.eventType(),
                delivered.payloadJson(), null, WebhookStatus.DELIVERED, 1, null, 204, delivered.createdAt()));
        WebhookDelivery failed = pendingWith("webhook-2", MAX_ATTEMPTS + 1, Duration.ofSeconds(10));
        repository.put(new WebhookDelivery(failed.webhookId(), failed.orderId(), failed.eventType(),
                failed.payloadJson(), null, WebhookStatus.FAILED, failed.attemptCount(), failed.nextAttemptAt(),
                500, failed.createdAt()));

        // when
        workerAt(Duration.ofSeconds(30), 100).retry();

        // then
        assertTrue(dispatched.isEmpty());
        assertEquals(WebhookStatus.DELIVERED, repository.findById("webhook-1").orElseThrow().status());
        assertEquals(WebhookStatus.FAILED, repository.findById("webhook-2").orElseThrow().status());
    }

    @Test
    @DisplayName("배치 크기를 넘겨 집지 않는다. 기한이 이른 것부터다")
    void retry_moreDueRowsThanBatchSize_dispatchesOldestFirst() {
        // given
        repository.put(pendingWith("webhook-late", 1, Duration.ofSeconds(20)));
        repository.put(pendingWith("webhook-early", 1, Duration.ofSeconds(10)));

        // when
        workerAt(Duration.ofSeconds(30), 1).retry();

        // then
        assertEquals(List.of("webhook-early"), dispatched);
    }

    @Test
    @DisplayName("한 건이 예외로 끝나도 나머지를 계속 보낸다")
    void retry_dispatcherThrows_continuesWithRemainingRows() {
        // given
        repository.put(pendingWith("webhook-1", 1, Duration.ofSeconds(10)));
        repository.put(pendingWith("webhook-2", 1, Duration.ofSeconds(11)));
        WebhookDispatcher flaky = webhookId -> {
            dispatched.add(webhookId);
            if (webhookId.equals("webhook-1")) {
                throw new IllegalStateException("발송기가 약속을 깼다");
            }
            return WebhookDispatcher.Result.DELIVERED;
        };

        // when
        workerAt(Duration.ofSeconds(30), 100, flaky).retry();

        // then
        assertEquals(List.of("webhook-1", "webhook-2"), dispatched);
    }

    @Test
    @DisplayName("배치 크기와 리스가 0 이하면 기동에서 막는다")
    void constructor_nonPositiveBatchSizeOrLease_rejected() {
        // when & then
        assertThrows(IllegalArgumentException.class, () -> workerAt(Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> new WebhookRetryWorker(repository,
                webhookId -> WebhookDispatcher.Result.DELIVERED, MerchantPropertiesFixture.defaults(),
                Clock.fixed(WebhookTestSupport.NOW, WebhookTestSupport.SEOUL), 100, Duration.ZERO));
    }

    @Test
    @DisplayName("보낼 것이 없으면 아무 일도 하지 않는다")
    void retry_emptyTable_doesNothing() {
        // when
        workerAt(Duration.ofSeconds(30), 100).retry();

        // then
        assertTrue(dispatched.isEmpty());
        assertEquals(0, repository.size());
    }

    @Test
    @DisplayName("리스는 집은 시각 기준이다")
    void retry_claimedRow_leaseIsRelativeToScanTime() {
        // given
        repository.put(pendingWith("webhook-1", 1, Duration.ofSeconds(10)));
        WebhookDispatcher inert = webhookId -> WebhookDispatcher.Result.SKIPPED_NO_SIGNING_KEY;

        // when
        workerAt(Duration.ofSeconds(30), 100, inert).retry();

        // then
        OffsetDateTime expected = WebhookTestSupport.NOW_SEOUL.plusSeconds(30).plusMinutes(1);
        assertEquals(expected, repository.findById("webhook-1").orElseThrow().nextAttemptAt());
    }
}
