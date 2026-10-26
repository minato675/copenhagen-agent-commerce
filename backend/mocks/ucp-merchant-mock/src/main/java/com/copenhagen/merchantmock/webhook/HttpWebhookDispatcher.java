package com.copenhagen.merchantmock.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.copenhagen.merchantmock.security.HttpMessageSignature;
import com.copenhagen.merchantmock.security.HttpMessageSignature.Components;
import com.copenhagen.merchantmock.security.HttpMessageSignature.Signed;
import com.copenhagen.merchantmock.security.SigningKeyProvider;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.scenario.ScenarioState;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.Audit;
import com.copenhagen.merchantmock.support.LogContext;
import com.copenhagen.merchantmock.support.Sleeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@code POST {webhook_url}} 한 번(DESIGN.md 8.6절). 다이제스트 → RFC 9421 서명 → 전송 → 결과 기록.
 *
 * <p>보내는 바이트는 항상 DB 에서 다시 읽은 {@code payload_json} 이다. jsonb 가 정규화한 형태라 첫 발송과
 * 재전송이 같은 바이트다(불변 조건 8번). 서명은 매번 새로 만든다(불변 조건 7번).
 *
 * <p>2xx 면 {@code DELIVERED}. 그 밖의 응답·타임아웃·연결 실패는 {@code attempt_count + 1} 과 지수 백오프
 * 시각을 적고 {@code PENDING} 으로 남긴다. 예외를 밖으로 내지 않는다. 개인키가 없으면 보내지 않는다.
 * 서명 없는 웹훅은 Core 가 거절하므로 보내는 것이 안 보내는 것보다 나쁘다.
 *
 * <p>시나리오 둘을 여기서 읽는다(9.3절). {@code WEBHOOK_DELAYED} 는 보내기 전에 기다리고, 서명 시각은 기다린
 * 뒤 잡는다. {@code WEBHOOK_DUPLICATE} 는 2xx 직후 같은 바이트·같은 헤더로 한 번 더 보낸다. 서명을 새로 만들지
 * 않는다 — 네트워크 재전송으로 같은 요청이 두 번 도착한 상황이라 두 요청이 바이트까지 같아야 한다. 두 번째 응답은
 * 기록하지 않는다. 이 시나리오가 검증하는 것은 내 상태가 아니라 Core 의 Inbox 다.
 */
@Component
public class HttpWebhookDispatcher implements WebhookDispatcher {

    static final String HEADER_UCP_AGENT = "UCP-Agent";
    static final String HEADER_WEBHOOK_ID = "Webhook-Id";
    static final String HEADER_WEBHOOK_TIMESTAMP = "Webhook-Timestamp";
    static final String HEADER_CONTENT_DIGEST = "Content-Digest";
    static final String HEADER_SIGNATURE_INPUT = "Signature-Input";
    static final String HEADER_SIGNATURE = "Signature";
    /** 추적용. Core 가 자기 로그와 맞춰 볼 수 있게 요청의 requestId 를 그대로 싣는다(12.1절). 서명 대상은 아니다. */
    static final String HEADER_REQUEST_ID = "X-Request-Id";

    private static final Logger LOG = LoggerFactory.getLogger(HttpWebhookDispatcher.class);

    private final WebhookRepository repository;
    private final WebhookTargetResolver targets;
    private final SigningKeyProvider signingKeys;
    private final MerchantProperties merchant;
    private final Clock clock;
    private final ScenarioState scenario;
    private final Sleeper sleeper;
    private final RestClient restClient;

    @Autowired
    public HttpWebhookDispatcher(WebhookRepository repository, WebhookTargetResolver targets,
            SigningKeyProvider signingKeys, MerchantProperties merchant, CoreProperties core, Clock clock,
            ScenarioState scenario) {
        this(repository, targets, signingKeys, merchant, clock, scenario, Sleeper.THREAD,
                RestClient.builder().requestFactory(requestFactory(core)));
    }

    /** 테스트 생성자. {@code MockRestServiceServer.bindTo(builder)} 로 묶은 빌더와 기록용 {@link Sleeper} 를 받는다. */
    HttpWebhookDispatcher(WebhookRepository repository, WebhookTargetResolver targets,
            SigningKeyProvider signingKeys, MerchantProperties merchant, Clock clock, ScenarioState scenario,
            Sleeper sleeper, RestClient.Builder builder) {
        this.repository = repository;
        this.targets = targets;
        this.signingKeys = signingKeys;
        this.merchant = merchant;
        this.clock = clock;
        this.scenario = scenario;
        this.sleeper = sleeper;
        this.restClient = builder.build();
    }

    @Override
    public Result dispatch(String webhookId) {
        Optional<WebhookDelivery> found = repository.findById(webhookId);
        if (found.isEmpty() || found.get().status() != WebhookStatus.PENDING) {
            LOG.info("보낼 것이 없다. webhookId={} status={}", webhookId,
                    found.map(WebhookDelivery::status).orElse(null));
            return Result.SKIPPED_NOT_PENDING;
        }
        WebhookDelivery delivery = found.get();
        try (LogContext.Scope scope = LogContext.open(
                LogContext.WEBHOOK_ID, webhookId, LogContext.ORDER_ID, delivery.orderId())) {
            return send(webhookId, delivery);
        }
    }

    private Result send(String webhookId, WebhookDelivery delivery) {
        if (!signingKeys.canSign()) {
            LOG.warn("서명 개인키가 없어 웹훅을 보내지 않는다. PENDING 으로 남긴다. webhookId={} orderId={}",
                    webhookId, delivery.orderId());
            return Result.SKIPPED_NO_SIGNING_KEY;
        }

        delayIfScenario(webhookId);

        URI target = targets.resolve();
        byte[] body = delivery.payloadJson().getBytes(StandardCharsets.UTF_8);
        String contentDigest = HttpMessageSignature.contentDigest(body);
        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Components components = new Components("POST", authorityOf(target), target.getRawPath(),
                contentDigest, MediaType.APPLICATION_JSON_VALUE);
        Signed signed = HttpMessageSignature.sign(components, now.toEpochSecond(), signingKeys.requireSigningKey());
        WebhookDelivery.Signed record = new WebhookDelivery.Signed(signed.signatureInput(), signed.signature(),
                contentDigest);

        Integer responseStatus = null;
        String reason;
        try {
            HttpStatusCode status = post(target, delivery, now, contentDigest, signed, body);
            responseStatus = status.value();
            if (status.is2xxSuccessful()) {
                repository.markDelivered(webhookId, status.value(), record);
                LOG.info("웹훅 전달. webhookId={} orderId={} event={} http={}", webhookId, delivery.orderId(),
                        delivery.eventType().value(), status.value());
                Audit.event("webhook.delivered").before(WebhookStatus.PENDING).after(WebhookStatus.DELIVERED)
                        .with("http", status.value()).with("attempt", delivery.attemptCount() + 1).log();
                duplicateIfScenario(target, delivery, now, contentDigest, signed, body);
                return Result.DELIVERED;
            }
            reason = "http_" + status.value();
            LOG.warn("웹훅이 거절됐다. 재시도를 예약한다. webhookId={} http={}", webhookId, status.value());
        } catch (RuntimeException exception) {
            reason = exception.getClass().getSimpleName();
            LOG.warn("웹훅을 보내지 못했다. 재시도를 예약한다. webhookId={} target={} cause={}", webhookId, target,
                    exception.toString());
        }
        OffsetDateTime nextAttemptAt = now.plus(backoff(delivery.attemptCount() + 1));
        repository.markRetry(webhookId, responseStatus, nextAttemptAt, record);
        Audit.event("webhook.retry_scheduled").before(WebhookStatus.PENDING).after(WebhookStatus.PENDING)
                .reason(reason).with("attempt", delivery.attemptCount() + 1)
                .with("nextAttemptAt", nextAttemptAt.toString()).log();
        return Result.RETRY_SCHEDULED;
    }

    private HttpStatusCode post(URI target, WebhookDelivery delivery, OffsetDateTime now, String contentDigest,
            Signed signed, byte[] body) {
        return restClient.post()
                .uri(target)
                .header(HEADER_UCP_AGENT, "profile=\"" + merchant.publicBaseUrl() + "/.well-known/ucp\"")
                .header(HEADER_WEBHOOK_ID, delivery.webhookId())
                .header(HEADER_WEBHOOK_TIMESTAMP, now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HEADER_CONTENT_DIGEST, contentDigest)
                .header(HEADER_SIGNATURE_INPUT, signed.signatureInput())
                .header(HEADER_SIGNATURE, signed.signature())
                .header(HEADER_REQUEST_ID, LogContext.requestIdOrNew())
                .body(body)
                .exchange((request, response) -> response.getStatusCode());
    }

    /**
     * {@code WEBHOOK_DELAYED}. 서명 전에 기다린다. 기다린 뒤 잡은 {@code created} 가 도착 시각과 맞아야 Core 의
     * 시각 검증에 걸리지 않는다. 인터럽트되면 플래그를 되살리고 남은 시간 없이 바로 보낸다.
     */
    private void delayIfScenario(String webhookId) {
        if (!scenario.is(Scenario.WEBHOOK_DELAYED)) {
            return;
        }
        Duration delay = merchant.webhook().scenarioDelay();
        LOG.info("시나리오 WEBHOOK_DELAYED. {} 기다린 뒤 보낸다. webhookId={}", delay, webhookId);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("지연 대기가 끊겼다. 바로 보낸다. webhookId={}", webhookId);
        }
    }

    /**
     * {@code WEBHOOK_DUPLICATE}. 첫 발송이 2xx 로 끝난 직후 같은 바이트·같은 헤더로 한 번 더 보낸다.
     * 두 번째 결과는 기록하지 않고 실패해도 무시한다. 행은 첫 응답으로 이미 {@code DELIVERED} 다.
     */
    private void duplicateIfScenario(URI target, WebhookDelivery delivery, OffsetDateTime now, String contentDigest,
            Signed signed, byte[] body) {
        if (!scenario.is(Scenario.WEBHOOK_DUPLICATE)) {
            return;
        }
        try {
            HttpStatusCode status = post(target, delivery, now, contentDigest, signed, body);
            LOG.info("시나리오 WEBHOOK_DUPLICATE. 같은 요청을 한 번 더 보냈다. webhookId={} http={}",
                    delivery.webhookId(), status.value());
        } catch (RuntimeException exception) {
            LOG.info("시나리오 WEBHOOK_DUPLICATE. 두 번째 발송이 실패했지만 무시한다. webhookId={} cause={}",
                    delivery.webhookId(), exception.toString());
        }
    }

    /** {@code initial × 2^(attempt-1)}. 1회 실패 뒤 10초, 2회 20초, 3회 40초(DESIGN.md 7.4절). */
    Duration backoff(int attempt) {
        Duration initial = merchant.webhook().retryInitialDelay();
        return initial.multipliedBy(1L << Math.max(0, Math.min(attempt - 1, 20)));
    }

    /** RFC 9421 {@code @authority}. Host 헤더 값과 같다. 기본 포트가 아니면 포트가 붙는다. */
    static String authorityOf(URI target) {
        return target.getPort() == -1 ? target.getHost() : target.getHost() + ":" + target.getPort();
    }

    private static JdkClientHttpRequestFactory requestFactory(CoreProperties core) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(core.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(core.requestTimeout());
        return factory;
    }
}
