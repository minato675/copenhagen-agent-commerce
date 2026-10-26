package com.copenhagen.merchantmock.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.Audit;
import com.copenhagen.merchantmock.support.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 웹훅 재전송 워커(Jira 184). 커밋 직후 첫 발송이 실패해 {@code PENDING} 으로 남은 행을 다시 보낸다.
 *
 * <p>세 워커 중 셋째다(DESIGN.md 8.4·8.7절). 인스턴스가 하나뿐이라 분산 락을 두지 않고 조건부 UPDATE 로만 집는다.
 *
 * <p>주기마다 둘을 순서대로 한다. 먼저 {@code retry-max-attempts} 를 넘긴 행을 {@code FAILED} 로 닫고,
 * 그다음 기한이 된 행을 집어 {@link WebhookDispatcher#dispatch} 에 넘긴다. 두 조건이 {@code attempt_count} 를
 * 기준으로 갈라져 있어 한 행이 같은 주기에 두 번 처리되지 않는다.
 *
 * <p><b>발송과 결과 기록은 여기 없다.</b> 전부 {@link HttpWebhookDispatcher} 안에 있다. 이 클래스가 아는 것은
 * 무엇을 언제 집을지뿐이다. 횟수 한도만 예외다 — 그것은 재전송 루프의 정책이지 발송기의 것이 아니라서
 * 커밋 직후 첫 발송에도 쓰이는 발송기에 넣지 않았다.
 *
 * <p>주기·배치·리스는 설정에서 직접 읽는다. {@code @Scheduled} 가 바인딩된 레코드를 못 보기 때문에
 * {@link MerchantProperties} 를 거치지 않는다(DESIGN.md 3.4절). 횟수 한도는 {@code @Scheduled} 밖이라
 * 레코드에서 읽는다.
 */
@Component
@ConditionalOnProperty(prefix = "merchant.webhook", name = "retry-enabled",
        havingValue = "true", matchIfMissing = true)
public class WebhookRetryWorker {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookRetryWorker.class);

    private final WebhookRepository repository;
    private final WebhookDispatcher dispatcher;
    private final MerchantProperties merchant;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;

    public WebhookRetryWorker(
            WebhookRepository repository,
            WebhookDispatcher dispatcher,
            MerchantProperties merchant,
            Clock clock,
            @Value("${merchant.webhook.retry-batch-size:100}") int batchSize,
            @Value("${merchant.webhook.retry-lease:PT1M}") Duration lease) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("merchant.webhook.retry-batch-size 는 1 이상이어야 한다");
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("merchant.webhook.retry-lease 는 0보다 커야 한다");
        }
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.merchant = merchant;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${merchant.webhook.retry-interval:PT15S}",
            initialDelayString = "${merchant.webhook.retry-interval:PT15S}")
    public void retry() {
        int maxAttempts = merchant.webhook().retryMaxAttempts();

        int failed = repository.failExhausted(maxAttempts, batchSize);
        if (failed > 0) {
            LOG.warn("웹훅 {}건이 {}회를 넘겨 FAILED 로 닫혔다. 사람이 확인해야 한다", failed, maxAttempts);
            try (LogContext.Scope scope = LogContext.openWorker("webhook-retry")) {
                Audit.event("webhook.failed").before(WebhookStatus.PENDING).after(WebhookStatus.FAILED)
                        .reason("max_attempts").with("count", failed).with("maxAttempts", maxAttempts).log();
            }
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<String> claimed = repository.claimDue(now, now.plus(lease), maxAttempts, batchSize);
        if (claimed.isEmpty()) {
            return;
        }
        LOG.info("재전송할 웹훅 {}건을 집었다", claimed.size());
        for (String webhookId : claimed) {
            send(webhookId);
        }
    }

    /**
     * 한 건이 터져도 나머지를 계속 보낸다. {@code dispatch} 는 예외를 내지 않기로 돼 있지만(8.6절) 그 약속이
     * 깨져도 배치 전체가 멈추면 안 된다. 집을 때 밀어 둔 리스 덕분에 이 행은 다음 주기에 다시 집힌다.
     */
    private void send(String webhookId) {
        try (LogContext.Scope scope = LogContext.openWorker("webhook-retry")) {
            dispatcher.dispatch(webhookId);
        } catch (RuntimeException exception) {
            LOG.error("웹훅 재전송이 예외로 끝났다. 다음 주기에 다시 집는다. webhookId={} cause={}",
                    webhookId, exception.toString());
        }
    }
}
