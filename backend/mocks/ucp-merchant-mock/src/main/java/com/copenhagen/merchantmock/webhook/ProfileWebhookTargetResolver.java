package com.copenhagen.merchantmock.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Core Platform Profile 의 {@code capabilities["dev.ucp.shopping.order"][0].config.webhook_url} 을 읽는다
 * (DESIGN.md 3.3절, 8.6절). 경로를 하드코딩하지 않는다.
 *
 * <p>Core 에 아직 Profile 이 없다(2026-09-16). 조회가 실패하거나 필드가 없으면 설정의 fallback 으로 간다.
 * 결과는 성공이든 fallback 이든 {@code core.profile-cache} 동안 캐시한다. 발송마다 Core 를 두드리지 않기 위해서다.
 */
@Component
public class ProfileWebhookTargetResolver implements WebhookTargetResolver {

    static final String ORDER_CAPABILITY = "dev.ucp.shopping.order";

    private static final Logger LOG = LoggerFactory.getLogger(ProfileWebhookTargetResolver.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final CoreProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RestClient restClient;

    private volatile Cached cached;

    private record Cached(URI target, Instant expiresAt) {
    }

    @Autowired
    public ProfileWebhookTargetResolver(CoreProperties properties, ObjectMapper objectMapper, Clock clock) {
        this(properties, objectMapper, clock, RestClient.builder().requestFactory(requestFactory(properties)));
    }

    /** 테스트 생성자. {@code MockRestServiceServer.bindTo(builder)} 로 묶은 빌더를 받는다. */
    ProfileWebhookTargetResolver(CoreProperties properties, ObjectMapper objectMapper, Clock clock,
            RestClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.restClient = builder.build();
    }

    @Override
    public URI resolve() {
        Cached current = cached;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.target();
        }
        URI target = fetchFromProfile();
        cached = new Cached(target, now.plus(properties.profileCache()));
        return target;
    }

    private URI fetchFromProfile() {
        try {
            String body = restClient.get()
                    .uri(properties.profileUrl())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(String.class);
            String webhookUrl = webhookUrlOf(body);
            if (webhookUrl != null) {
                LOG.info("Core Profile 에서 웹훅 주소를 읽었다. webhookUrl={}", webhookUrl);
                return URI.create(webhookUrl);
            }
            LOG.warn("Core Profile 에 {} 의 config.webhook_url 이 없다. fallback 을 쓴다. fallback={}",
                    ORDER_CAPABILITY, properties.webhookUrlFallback());
        } catch (RuntimeException exception) {
            LOG.warn("Core Profile 을 읽지 못했다. fallback 을 쓴다. profile={} fallback={} cause={}",
                    properties.profileUrl(), properties.webhookUrlFallback(), exception.toString());
        }
        return URI.create(properties.webhookUrlFallback());
    }

    @SuppressWarnings("unchecked")
    private String webhookUrlOf(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Map<String, Object> root = objectMapper.readValue(body, JSON_OBJECT);
        Object ucp = root.get("ucp");
        if (!(ucp instanceof Map<?, ?> ucpMap)) {
            return null;
        }
        Object capabilities = ucpMap.get("capabilities");
        if (!(capabilities instanceof Map<?, ?> capabilityMap)) {
            return null;
        }
        Object order = capabilityMap.get(ORDER_CAPABILITY);
        if (!(order instanceof List<?> entries) || entries.isEmpty()
                || !(entries.get(0) instanceof Map<?, ?> first)) {
            return null;
        }
        Object config = first.get("config");
        if (!(config instanceof Map<?, ?> configMap)) {
            return null;
        }
        Object url = configMap.get("webhook_url");
        return url instanceof String text && !text.isBlank() ? text : null;
    }

    private static JdkClientHttpRequestFactory requestFactory(CoreProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.requestTimeout());
        return factory;
    }
}
