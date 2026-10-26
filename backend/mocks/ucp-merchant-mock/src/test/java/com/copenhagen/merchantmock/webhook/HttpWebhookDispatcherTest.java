package com.copenhagen.merchantmock.webhook;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.copenhagen.merchantmock.security.HttpMessageSignature;
import com.copenhagen.merchantmock.security.HttpMessageSignature.Components;
import com.copenhagen.merchantmock.security.SigningKeyProvider;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.scenario.ScenarioState;
import com.copenhagen.merchantmock.support.TestSigningKeys;
import com.copenhagen.merchantmock.webhook.WebhookDispatcher.Result;
import com.nimbusds.jose.jwk.ECKey;
import com.copenhagen.merchantmock.support.LogContext;
import org.slf4j.MDC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * 웹훅 한 건의 발송(DESIGN.md 8.6절). Core 는 MockRestServiceServer 가 흉내 낸다.
 *
 * <p>핵심은 Core 가 할 검증을 여기서 미리 하는 것이다. 받은 헤더 일곱을 읽고 내 공개키로 서명을 되풀이해
 * 확인한다. 이게 통하면 Core 가 같은 절차로 검증할 수 있다.
 */
class HttpWebhookDispatcherTest {

    private static final URI TARGET = URI.create("http://core-service:8080/webhooks/ucp/orders");
    private static final String WEBHOOK_ID = "webhook-1";

    private final ECKey key = TestSigningKeys.generate();
    private final Clock clock = Clock.fixed(WebhookTestSupport.NOW, WebhookTestSupport.SEOUL);
    private final ScenarioState scenario = new ScenarioState(Scenario.NORMAL);
    private final List<Duration> slept = new ArrayList<>();
    private InMemoryWebhookRepository repository;
    private MockRestServiceServer server;
    private HttpWebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        repository = new InMemoryWebhookRepository();
        repository.insert(WebhookTestSupport.pendingCreated(WEBHOOK_ID));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        dispatcher = dispatcher(TestSigningKeys.providerSigningWith(key), builder);
    }

    /** 실제로 잠들지 않는다. 기다리라고 한 시간만 기록한다. */
    private HttpWebhookDispatcher dispatcher(SigningKeyProvider keys, RestClient.Builder builder) {
        return new HttpWebhookDispatcher(repository, () -> TARGET, keys,
                TestSigningKeys.propertiesSigningWith(key), clock, scenario, slept::add, builder);
    }

    @Test
    @DisplayName("헤더 일곱을 붙여 보내고 그 서명이 내 공개키로 검증된다")
    void dispatch_pendingRow_sendsSignedRequestVerifiableWithPublicKey() {
        // given
        server.expect(requestTo(TARGET.toString()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    HttpHeaders headers = request.getHeaders();
                    byte[] body = ((MockClientHttpRequest) request).getBodyAsBytes();
                    String digest = HttpMessageSignature.contentDigest(body);

                    assertEquals("profile=\"http://ucp-merchant-mock:8080/.well-known/ucp\"",
                            headers.getFirst(HttpWebhookDispatcher.HEADER_UCP_AGENT));
                    assertEquals(WEBHOOK_ID, headers.getFirst(HttpWebhookDispatcher.HEADER_WEBHOOK_ID));
                    assertEquals(WebhookTestSupport.EXPECTED_TIMESTAMP,
                            headers.getFirst(HttpWebhookDispatcher.HEADER_WEBHOOK_TIMESTAMP));
                    assertTrue(headers.getContentType().isCompatibleWith(MediaType.APPLICATION_JSON));
                    assertEquals(digest, headers.getFirst(HttpWebhookDispatcher.HEADER_CONTENT_DIGEST),
                            "Content-Digest 는 실제로 보낸 바이트의 해시여야 한다");
                    assertEquals("{\"order\":" + WebhookTestSupport.RAW_ORDER_JSON + "}",
                            new String(body, StandardCharsets.UTF_8));

                    String signatureInput = headers.getFirst(HttpWebhookDispatcher.HEADER_SIGNATURE_INPUT);
                    String signature = headers.getFirst(HttpWebhookDispatcher.HEADER_SIGNATURE);
                    assertNotNull(signatureInput);
                    assertNotNull(signature);
                    assertEquals(WebhookTestSupport.NOW.getEpochSecond(),
                            HttpMessageSignature.createdOf(signatureInput));
                    Components components = new Components("POST", "core-service:8080", "/webhooks/ucp/orders",
                            digest, MediaType.APPLICATION_JSON_VALUE);
                    assertTrue(HttpMessageSignature.verify(components, signatureInput, signature, key.toPublicJWK()),
                            "Core 가 내 Profile 공개키로 검증하는 절차 그대로 통과해야 한다");
                })
                .andRespond(withNoContent());

        // when
        Result result = dispatcher.dispatch(WEBHOOK_ID);

        // then
        assertEquals(Result.DELIVERED, result);
        WebhookDelivery stored = repository.findById(WEBHOOK_ID).orElseThrow();
        assertEquals(WebhookStatus.DELIVERED, stored.status());
        assertEquals(1, stored.attemptCount());
        assertEquals(204, stored.responseStatus());
        assertNull(stored.nextAttemptAt());
        assertNotNull(stored.signed().signature(), "마지막 발송 서명을 기록한다");
        server.verify();
    }

    @Test
    @DisplayName("2xx 가 아니면 PENDING 으로 두고 첫 재시도를 10초 뒤로 잡는다")
    void dispatch_serverError_schedulesRetryWithInitialDelay() {
        // given
        server.expect(requestTo(TARGET.toString())).andRespond(withServerError());

        // when
        Result result = dispatcher.dispatch(WEBHOOK_ID);

        // then
        assertEquals(Result.RETRY_SCHEDULED, result);
        WebhookDelivery stored = repository.findById(WEBHOOK_ID).orElseThrow();
        assertEquals(WebhookStatus.PENDING, stored.status());
        assertEquals(1, stored.attemptCount());
        assertEquals(500, stored.responseStatus());
        assertEquals(WebhookTestSupport.NOW_SEOUL.plusSeconds(10).toInstant(), stored.nextAttemptAt().toInstant());
    }

    @Test
    @DisplayName("두 번째 실패는 20초 뒤다. 지수 백오프")
    void dispatch_secondFailure_doublesDelay() {
        // given
        server.expect(requestTo(TARGET.toString())).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(TARGET.toString())).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        // when
        dispatcher.dispatch(WEBHOOK_ID);
        dispatcher.dispatch(WEBHOOK_ID);

        // then
        WebhookDelivery stored = repository.findById(WEBHOOK_ID).orElseThrow();
        assertEquals(2, stored.attemptCount());
        assertEquals(WebhookTestSupport.NOW_SEOUL.plusSeconds(20).toInstant(), stored.nextAttemptAt().toInstant());
        assertEquals(Duration.ofSeconds(40), dispatcher.backoff(3));
        assertEquals(Duration.ofSeconds(10), dispatcher.backoff(1));
    }

    @Test
    @DisplayName("연결이 끊기면 응답 코드 없이 재시도를 예약한다")
    void dispatch_ioFailure_schedulesRetryWithoutStatus() {
        // given
        server.expect(requestTo(TARGET.toString())).andRespond(request -> {
            throw new IOException("connection refused");
        });

        // when
        Result result = dispatcher.dispatch(WEBHOOK_ID);

        // then
        assertEquals(Result.RETRY_SCHEDULED, result);
        WebhookDelivery stored = repository.findById(WEBHOOK_ID).orElseThrow();
        assertEquals(WebhookStatus.PENDING, stored.status());
        assertNull(stored.responseStatus());
        assertEquals(1, stored.attemptCount());
    }

    @Test
    @DisplayName("이미 전달된 행이나 없는 행은 건너뛴다")
    void dispatch_notPending_skips() {
        // given
        repository.markDelivered(WEBHOOK_ID, 204, new WebhookDelivery.Signed("i", "s", "d"));

        // when & then
        assertEquals(Result.SKIPPED_NOT_PENDING, dispatcher.dispatch(WEBHOOK_ID));
        assertEquals(Result.SKIPPED_NOT_PENDING, dispatcher.dispatch("nope"));
        server.verify();
    }

    @Test
    @DisplayName("@authority 는 기본 포트가 아니면 포트를 붙인다")
    void authorityOf_variousUris_matchesHostHeader() {
        // when & then
        assertEquals("core-service:8080", HttpWebhookDispatcher.authorityOf(TARGET));
        assertEquals("core.example", HttpWebhookDispatcher.authorityOf(URI.create("https://core.example/x")));
    }

    @Test
    @DisplayName("요청 컨텍스트의 requestId 를 X-Request-Id 로 같이 보낸다. 서명 대상은 아니고 추적용이다")
    void dispatch_withRequestContext_sendsRequestIdHeaderAndClosesScope() {
        // given
        MDC.put(LogContext.REQUEST_ID, "core-req-42");
        server.expect(requestTo(TARGET.toString()))
                .andExpect(header(HttpWebhookDispatcher.HEADER_REQUEST_ID, "core-req-42"))
                .andRespond(withNoContent());

        try {
            // when
            Result result = dispatcher.dispatch(WEBHOOK_ID);

            // then
            assertEquals(Result.DELIVERED, result);
            assertNull(MDC.get(LogContext.WEBHOOK_ID), "발송이 끝나면 webhookId 범위는 닫힌다");
            assertEquals("core-req-42", MDC.get(LogContext.REQUEST_ID), "요청의 requestId 는 건드리지 않는다");
            server.verify();
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("NORMAL 에서는 기다리지도 않고 두 번 보내지도 않는다")
    void dispatch_normalScenario_neitherWaitsNorDuplicates() {
        // given
        server.expect(times(1), requestTo(TARGET.toString())).andRespond(withNoContent());

        // when
        dispatcher.dispatch(WEBHOOK_ID);

        // then
        assertTrue(slept.isEmpty());
        server.verify();
    }

    @Test
    @DisplayName("WEBHOOK_DELAYED 면 설정 시간만큼 기다린 뒤 한 번 보낸다")
    void dispatch_delayedScenario_waitsConfiguredDelayThenSendsOnce() {
        // given
        scenario.set(Scenario.WEBHOOK_DELAYED);
        server.expect(times(1), requestTo(TARGET.toString())).andRespond(withNoContent());

        // when
        Result result = dispatcher.dispatch(WEBHOOK_ID);

        // then
        assertEquals(Result.DELIVERED, result);
        assertEquals(List.of(Duration.ofSeconds(5)), slept, "fixture 의 scenario-delay 는 PT5S 다");
        assertEquals(WebhookStatus.DELIVERED, repository.findById(WEBHOOK_ID).orElseThrow().status());
        server.verify();
    }

    @Test
    @DisplayName("WEBHOOK_DUPLICATE 면 2xx 뒤 같은 바이트·같은 서명으로 한 번 더 보내고 행은 한 번 전달로 남는다")
    void dispatch_duplicateScenario_repostsIdenticalRequestOnce() {
        // given
        scenario.set(Scenario.WEBHOOK_DUPLICATE);
        List<String> signatures = new ArrayList<>();
        List<String> signatureInputs = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        server.expect(times(2), requestTo(TARGET.toString()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    HttpHeaders headers = request.getHeaders();
                    assertEquals(WEBHOOK_ID, headers.getFirst(HttpWebhookDispatcher.HEADER_WEBHOOK_ID));
                    signatures.add(headers.getFirst(HttpWebhookDispatcher.HEADER_SIGNATURE));
                    signatureInputs.add(headers.getFirst(HttpWebhookDispatcher.HEADER_SIGNATURE_INPUT));
                    bodies.add(new String(((MockClientHttpRequest) request).getBodyAsBytes(), StandardCharsets.UTF_8));
                })
                .andRespond(withNoContent());

        // when
        Result result = dispatcher.dispatch(WEBHOOK_ID);

        // then
        assertEquals(Result.DELIVERED, result);
        assertEquals(2, signatures.size());
        assertEquals(signatures.get(0), signatures.get(1), "두 번째는 재서명이 아니라 같은 서명이어야 한다");
        assertEquals(signatureInputs.get(0), signatureInputs.get(1));
        assertEquals(bodies.get(0), bodies.get(1));
        WebhookDelivery stored = repository.findById(WEBHOOK_ID).orElseThrow();
        assertEquals(WebhookStatus.DELIVERED, stored.status());
        assertEquals(1, stored.attemptCount(), "두 번째 발송은 기록하지 않는다");
        assertTrue(slept.isEmpty());
        server.verify();
    }

    @Test
    @DisplayName("WEBHOOK_DUPLICATE 의 두 번째 발송이 실패해도 첫 결과는 DELIVERED 그대로다")
    void dispatch_duplicateScenario_secondFailureIsIgnored() {
        // given
        scenario.set(Scenario.WEBHOOK_DUPLICATE);
        server.expect(requestTo(TARGET.toString())).andRespond(withNoContent());
        server.expect(requestTo(TARGET.toString())).andRespond(withServerError());

        // when
        Result result = dispatcher.dispatch(WEBHOOK_ID);

        // then
        assertEquals(Result.DELIVERED, result);
        WebhookDelivery stored = repository.findById(WEBHOOK_ID).orElseThrow();
        assertEquals(WebhookStatus.DELIVERED, stored.status());
        assertEquals(204, stored.responseStatus());
        server.verify();
    }
}
