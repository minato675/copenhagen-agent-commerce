package com.copenhagen.merchantmock.webhook;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 웹훅 주소는 Core Profile 에서 읽고, 못 읽으면 fallback, 읽은 값은 캐시한다(DESIGN.md 8.6절). */
class ProfileWebhookTargetResolverTest {

    private static final String PROFILE_URL = "http://core-service:8080/.well-known/ucp";
    private static final String PROFILE_WITH_WEBHOOK = """
            {"ucp":{"version":"2026-08-25","capabilities":{
              "dev.ucp.shopping.checkout":[{"version":"2026-08-25"}],
              "dev.ucp.shopping.order":[{"version":"2026-08-25",
                "config":{"webhook_url":"http://core-service:8080/webhooks/v1/ucp/orders"}}]}},
             "keys":[]}
            """;
    private static final String PROFILE_WITHOUT_WEBHOOK = """
            {"ucp":{"version":"2026-08-25","capabilities":{"dev.ucp.shopping.order":[{"version":"2026-08-25"}]}},
             "keys":[]}
            """;

    private final CoreProperties properties = new CoreProperties("http://core-service:8080/", null,
            Duration.ofMinutes(5), null, null);
    private MutableClock clock;
    private MockRestServiceServer server;
    private ProfileWebhookTargetResolver resolver;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(WebhookTestSupport.NOW);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        resolver = new ProfileWebhookTargetResolver(properties, JsonMapper.builder().build(), clock, builder);
    }

    @Test
    @DisplayName("Profile 의 dev.ucp.shopping.order config.webhook_url 을 쓴다")
    void resolve_profileHasWebhookUrl_usesIt() {
        // given
        server.expect(requestTo(PROFILE_URL)).andRespond(withSuccess(PROFILE_WITH_WEBHOOK, MediaType.APPLICATION_JSON));

        // when & then
        assertEquals(URI.create("http://core-service:8080/webhooks/v1/ucp/orders"), resolver.resolve());
        server.verify();
    }

    @Test
    @DisplayName("Profile 을 못 읽으면 fallback 이다. 기본은 {core}/webhooks/v1/ucp/orders")
    void resolve_profileUnavailable_fallsBack() {
        // given
        server.expect(requestTo(PROFILE_URL)).andRespond(withServerError());

        // when & then
        assertEquals(URI.create("http://core-service:8080/webhooks/v1/ucp/orders"), resolver.resolve());
    }

    @Test
    @DisplayName("Profile 에 webhook_url 이 없으면 fallback 이다")
    void resolve_profileWithoutWebhookUrl_fallsBack() {
        // given
        server.expect(requestTo(PROFILE_URL))
                .andRespond(withSuccess(PROFILE_WITHOUT_WEBHOOK, MediaType.APPLICATION_JSON));

        // when & then
        assertEquals(URI.create("http://core-service:8080/webhooks/v1/ucp/orders"), resolver.resolve());
    }

    @Test
    @DisplayName("캐시 시간 안에는 Profile 을 다시 읽지 않고, 지나면 다시 읽는다")
    void resolve_withinCache_doesNotRefetchUntilExpiry() {
        // given
        server.expect(requestTo(PROFILE_URL)).andRespond(withSuccess(PROFILE_WITH_WEBHOOK, MediaType.APPLICATION_JSON));
        server.expect(requestTo(PROFILE_URL)).andRespond(withServerError());

        // when
        URI first = resolver.resolve();
        clock.advance(Duration.ofMinutes(4));
        URI second = resolver.resolve();
        clock.advance(Duration.ofMinutes(2));
        URI third = resolver.resolve();

        // then
        assertEquals(first, second, "5분 안에는 같은 값이고 요청도 한 번뿐이다");
        assertEquals(URI.create("http://core-service:8080/webhooks/v1/ucp/orders"), third,
                "만료 뒤 다시 읽었고 이번엔 실패해 fallback 이다");
        server.verify();
    }

    @Test
    @DisplayName("설정에 fallback 을 주면 그 값이고 base-url 의 끝 슬래시는 정리된다")
    void coreProperties_defaults_normalizeUrls() {
        // when
        CoreProperties custom = new CoreProperties("http://core:8080/", "http://core:8080/webhooks/v1/ucp/orders",
                null, null, null);

        // then
        assertEquals("http://core:8080/.well-known/ucp", custom.profileUrl());
        assertEquals("http://core:8080/webhooks/v1/ucp/orders", custom.webhookUrlFallback());
        assertEquals("http://core-service:8080/webhooks/v1/ucp/orders", properties.webhookUrlFallback());
        assertEquals(Duration.ofMinutes(5), custom.profileCache());
    }

    /** 앞으로만 움직이는 시계. 캐시 만료를 시험하는 데 쓴다. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return WebhookTestSupport.SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
