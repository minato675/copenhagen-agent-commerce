package com.copenhagen.merchantmock.paymenthandler;

import java.net.http.HttpClient;
import java.util.Map;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.support.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST {pay}/merchant/v1/payments} 를 한 번 부른다(Jira 179, DESIGN.md 8.5절).
 *
 * <p>응답은 세 갈래로 접는다. 2xx 는 본문 status 그대로, 4xx 는 {@code FAILED}(Pay 가 처리하지 않은 것이
 * 확실하다), 그 밖의 모든 것은 {@code UNKNOWN} 이다. 어떤 경우에도 예외를 밖으로 내지 않는다.
 *
 * <p>{@code GET /merchant/v1/payments/&#123;paymentId&#125;} 도 여기 있다(183). 조회는 4xx 에서 청구와 갈린다.
 * 조회가 거절된 것은 결제 결과가 아니므로 {@code FAILED} 가 아니라 {@code UNKNOWN} 이다(DESIGN.md 8.8절).
 *
 * <p>RestClient 는 직접 조립한다. Boot 4 가 자동설정을 기술별 모듈로 쪼개서 {@code RestClient.Builder}
 * 빈이 있는지 확신할 수 없기 때문이다(Flyway 건과 같다. DESIGN.md 15.2절). JSON 도 메시지 컨버터에 기대지
 * 않고 {@link ObjectMapper} 로 문자열을 직접 쓰고 읽는다.
 *
 * <p>로그에 {@code credentialId} 와 {@code checkoutHash} 를 남기지 않는다. Pay 도 같은 두 값을 가린다.
 */
@Component
public class HttpPayClient implements PayClient {

    static final String PAYMENTS_PATH = "/merchant/v1/payments";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    static final String ERROR_UNREACHABLE = "PAY_UNREACHABLE";
    static final String ERROR_MALFORMED_BODY = "PAY_MALFORMED_BODY";
    static final String ERROR_STATUS_UNRECOGNIZED = "PAY_STATUS_UNRECOGNIZED";
    static final String ERROR_HTTP_PREFIX = "PAY_HTTP_";

    /**
     * Pay 의 {@code PayErrorCode.IDEMPOTENCY_CONFLICT}. 같은 멱등 키에 다른 본문을 보냈을 때 돌아온다.
     *
     * <p>재POST(183)에서 이 코드를 받으면 결제가 실패한 것이 아니라 내가 되살린 본문이 처음 보낸 것과
     * 다르다는 뜻이다. Pay 는 본문을 JCS 로 정규화한 뒤 해시하므로 키 순서나 공백 때문일 수는 없다.
     * 값이 실제로 다르다 — 내 버그다(DESIGN.md 8.8절).
     */
    static final String ERROR_IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    private static final Logger LOG = LoggerFactory.getLogger(HttpPayClient.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final PayProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /** 운영 생성자. 타임아웃 둘을 건 JDK HttpClient 위에 RestClient 를 올린다. */
    @Autowired
    public HttpPayClient(PayProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, RestClient.builder().requestFactory(requestFactory(properties)));
    }

    /** 테스트 생성자. {@code MockRestServiceServer.bindTo(builder)} 로 묶은 빌더를 그대로 받는다. */
    HttpPayClient(PayProperties properties, ObjectMapper objectMapper, RestClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public PaymentResult charge(PaymentRequest request) {
        String requestId = LogContext.requestIdOrNew();
        try {
            PaymentResult result = restClient.post()
                    .uri(PAYMENTS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.tokenFor(request.merchantId()))
                    .header(IDEMPOTENCY_KEY_HEADER, request.idempotencyKey())
                    .header(REQUEST_ID_HEADER, requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request.toBody()))
                    .exchange((httpRequest, response) ->
                            map(response.getStatusCode(), response.bodyTo(String.class)));
            LOG.info("Pay 청구 응답. checkoutId={} requestId={} status={} paymentId={} errorCode={}",
                    request.businessCheckoutId(), requestId, result.status(), result.paymentId(),
                    result.errorCode());
            return result;
        } catch (RuntimeException exception) {
            // 연결 실패, 타임아웃, 응답 스트림 끊김. Pay 가 처리했는지 알 수 없으므로 UNKNOWN 이다.
            LOG.warn("Pay 청구 응답을 받지 못했다. UNKNOWN 으로 남긴다. checkoutId={} requestId={} cause={}",
                    request.businessCheckoutId(), requestId, exception.toString());
            return PaymentResult.unknown(ERROR_UNREACHABLE);
        }
    }

    @Override
    public PaymentResult lookup(String merchantId, String paymentId) {
        String requestId = LogContext.requestIdOrNew();
        try {
            PaymentResult result = restClient.get()
                    .uri(PAYMENTS_PATH + "/{paymentId}", paymentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.tokenFor(merchantId))
                    .header(REQUEST_ID_HEADER, requestId)
                    .exchange((httpRequest, response) ->
                            mapLookup(response.getStatusCode(), response.bodyTo(String.class)));
            LOG.info("Pay 결제 조회 응답. paymentId={} requestId={} status={} errorCode={}",
                    paymentId, requestId, result.status(), result.errorCode());
            return result;
        } catch (RuntimeException exception) {
            LOG.warn("Pay 결제 조회 응답을 받지 못했다. UNKNOWN 으로 남긴다. paymentId={} requestId={} cause={}",
                    paymentId, requestId, exception.toString());
            return PaymentResult.unknown(ERROR_UNREACHABLE);
        }
    }

    /**
     * 조회 응답을 접는다. 청구({@link #map})와 4xx 에서 갈린다.
     *
     * <p>청구의 4xx 는 "Pay 가 처리하지 않았다" 라서 {@code FAILED} 가 맞다. 조회의 4xx 는 결제 결과가 아니다.
     * 404 는 그 결제가 없다는 뜻이고 401·403 은 내 설정 문제다. 어느 쪽도 "결제가 실패했다" 가 아니므로
     * {@code UNKNOWN} 을 유지한다. 사실이 아닌 것을 기록하지 않는다(DESIGN.md 8.8절).
     */
    PaymentResult mapLookup(HttpStatusCode status, String body) {
        if (status.is2xxSuccessful()) {
            return fromPaymentBody(body);
        }
        String code = status.is4xxClientError()
                ? errorCodeOf(body, ERROR_HTTP_PREFIX + status.value())
                : ERROR_HTTP_PREFIX + status.value();
        LOG.warn("Pay 결제 조회가 실패했다. UNKNOWN 을 유지한다. http={} code={}", status.value(), code);
        return PaymentResult.unknown(code);
    }

    /** 상태 코드와 본문을 결과로 접는다. 여기서 던지는 예외는 없다. */
    PaymentResult map(HttpStatusCode status, String body) {
        if (status.is2xxSuccessful()) {
            return fromPaymentBody(body);
        }
        if (status.is4xxClientError()) {
            String code = errorCodeOf(body, ERROR_HTTP_PREFIX + status.value());
            if (status.value() == 401 || status.value() == 403) {
                LOG.warn("Pay 가 인증을 거절했다. services.pay.bearer-token 과 merchant.id 가 Pay 의 매핑과 한 쌍인지 "
                        + "확인한다. http={} code={}", status.value(), code);
            } else {
                LOG.warn("Pay 가 청구를 거절했다. 처리되지 않았으므로 FAILED. http={} code={}", status.value(), code);
            }
            return PaymentResult.failed(code);
        }
        LOG.warn("Pay 가 5xx 를 돌려줬다. 처리됐는지 알 수 없으므로 UNKNOWN. http={}", status.value());
        return PaymentResult.unknown(ERROR_HTTP_PREFIX + status.value());
    }

    private PaymentResult fromPaymentBody(String body) {
        Map<String, Object> json = parse(body);
        if (json == null) {
            LOG.warn("Pay 2xx 응답 본문을 읽을 수 없다. UNKNOWN 으로 남긴다");
            return PaymentResult.unknown(ERROR_MALFORMED_BODY);
        }
        String paymentId = text(json, "paymentId");
        String rawStatus = text(json, "status");
        PaymentStatus paymentStatus;
        try {
            paymentStatus = PaymentStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException | NullPointerException exception) {
            // Pay 가 상태 값을 늘렸다. 공유 enum 이 먼저 바뀌어야 한다(DESIGN.md 3.1절). 그때까지는 모르는 값이다.
            LOG.warn("Pay 가 모르는 status 를 돌려줬다. UNKNOWN 으로 남긴다. status={} paymentId={}", rawStatus,
                    paymentId);
            return new PaymentResult(PaymentStatus.UNKNOWN, paymentId, null, ERROR_STATUS_UNRECOGNIZED, null);
        }
        return new PaymentResult(paymentStatus, paymentId, text(json, "pgTransactionId"),
                text(json, "errorCode"), text(json, "nextAction"));
    }

    /** 오류 본문의 코드. Pay 오류 형식은 {@code code} 이고(19.1절) 팀 공통 형식은 {@code errorCode} 다. 둘 다 본다. */
    private String errorCodeOf(String body, String fallback) {
        Map<String, Object> json = parse(body);
        if (json == null) {
            return fallback;
        }
        String code = text(json, "code");
        if (code == null) {
            code = text(json, "errorCode");
        }
        return code == null ? fallback : code;
    }

    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, JSON_OBJECT);
        } catch (JacksonException exception) {
            return null;
        }
    }

    private static String text(Map<String, Object> json, String key) {
        Object value = json.get(key);
        return value == null ? null : value.toString();
    }

    private static JdkClientHttpRequestFactory requestFactory(PayProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.requestTimeout());
        return factory;
    }
}
