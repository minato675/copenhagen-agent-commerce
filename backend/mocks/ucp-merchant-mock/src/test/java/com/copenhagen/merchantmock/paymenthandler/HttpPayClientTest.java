package com.copenhagen.merchantmock.paymenthandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import com.copenhagen.contract.internalapi.PaymentStatus;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pay 청구 클라이언트의 응답 매핑(DESIGN.md 8.5절). 실제 HTTP 대신 MockRestServiceServer 가 Pay 를 흉내 낸다.
 *
 * <p>Pay 응답 본문은 pay-service 의 {@code MerchantPaymentController.PaymentResponse} 모양을 그대로 옮겼다.
 */
class HttpPayClientTest {

    private static final String BASE_URL = "http://pay-service:8080";
    private static final String PAYMENTS_URL = BASE_URL + HttpPayClient.PAYMENTS_PATH;
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private HttpPayClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpPayClient(new PayProperties(BASE_URL, "test-token", "", null, null, null, 0),
                objectMapper, builder);
    }

    @Test
    @DisplayName("성공 응답이면 SUCCESS 와 paymentId, pgTransactionId 를 돌려준다")
    void charge_successBody_returnsSuccessWithIds() {
        // given
        server.expect(requestTo(PAYMENTS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"paymentId":"pay-1","status":"SUCCESS","amountMinor":92000,"currency":"KRW",
                         "pgTransactionId":"pg-1","nextAction":"CREATE_ORDER"}
                        """, MediaType.APPLICATION_JSON));

        // when
        PaymentResult result = client.charge(request());

        // then
        assertEquals(PaymentStatus.SUCCESS, result.status());
        assertTrue(result.isSuccess());
        assertEquals("pay-1", result.paymentId());
        assertEquals("pg-1", result.pgTransactionId());
        assertEquals("CREATE_ORDER", result.nextAction());
        assertNull(result.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("헤더 셋과 본문 여섯 필드를 Pay 규격대로 보낸다")
    void charge_anyRequest_sendsHeadersAndBodyAsPayExpects() {
        // given
        server.expect(requestTo(PAYMENTS_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(header(HttpPayClient.IDEMPOTENCY_KEY_HEADER, request().idempotencyKey()))
                .andExpect(request -> {
                    assertNotNull(request.getHeaders().getFirst(HttpPayClient.REQUEST_ID_HEADER),
                            "X-Request-Id 는 Pay 가 필수로 요구한다");
                    assertTrue(request.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON));
                    Map<String, Object> body = objectMapper.readValue(
                            ((MockClientHttpRequest) request).getBodyAsString(), JSON_OBJECT);
                    assertEquals(Set.of("merchantId", "businessCheckoutId", "checkoutHash", "amountMinor",
                            "currency", "credentialId"), body.keySet());
                    assertEquals("merchant-a", body.get("merchantId"));
                    assertEquals("checkout-1", body.get("businessCheckoutId"));
                    assertEquals("sha256:abc", body.get("checkoutHash"));
                    assertEquals(92000L, ((Number) body.get("amountMinor")).longValue());
                    assertEquals("KRW", body.get("currency"));
                    assertEquals("cred-1", body.get("credentialId"));
                })
                .andRespond(withSuccess(successBody("SUCCESS"), MediaType.APPLICATION_JSON));

        // when
        client.charge(request());

        // then
        server.verify();
    }

    @Test
    @DisplayName("PG 거절 응답이면 FAILED 와 errorCode 를 그대로 돌려준다")
    void charge_failedBody_returnsFailedWithErrorCode() {
        // given
        server.expect(requestTo(PAYMENTS_URL)).andRespond(withSuccess("""
                {"paymentId":"pay-1","status":"FAILED","amountMinor":92000,"currency":"KRW",
                 "errorCode":"PG_DECLINED","nextAction":"SHOW_PAYMENT_FAILURE"}
                """, MediaType.APPLICATION_JSON));

        // when
        PaymentResult result = client.charge(request());

        // then
        assertEquals(PaymentStatus.FAILED, result.status());
        assertEquals("pay-1", result.paymentId());
        assertEquals("PG_DECLINED", result.errorCode());
        assertEquals("SHOW_PAYMENT_FAILURE", result.nextAction());
        assertNull(result.pgTransactionId());
    }

    @Test
    @DisplayName("UNKNOWN 본문이어도 paymentId 는 남긴다. 재조회 열쇠다")
    void charge_unknownBody_keepsPaymentIdForReconciliation() {
        // given
        server.expect(requestTo(PAYMENTS_URL)).andRespond(withSuccess("""
                {"paymentId":"pay-1","status":"UNKNOWN","amountMinor":92000,"currency":"KRW",
                 "nextAction":"WAIT_FOR_PAYMENT_STATUS"}
                """, MediaType.APPLICATION_JSON));

        // when
        PaymentResult result = client.charge(request());

        // then
        assertEquals(PaymentStatus.UNKNOWN, result.status());
        assertEquals("pay-1", result.paymentId());
        assertNull(result.errorCode());
    }

    @Test
    @DisplayName("4xx 는 Pay 가 처리하지 않은 것이라 FAILED 이고 본문의 code 를 errorCode 로 남긴다")
    void charge_clientError_returnsFailedWithPayErrorCode() {
        // given
        server.expect(requestTo(PAYMENTS_URL)).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"code":"CREDENTIAL_ALREADY_CONSUMED","message":"이미 사용된 Credential 입니다.",
                         "requestId":"req-1","retryable":false}
                        """));

        // when
        PaymentResult result = client.charge(request());

        // then
        assertEquals(PaymentStatus.FAILED, result.status());
        assertEquals("CREDENTIAL_ALREADY_CONSUMED", result.errorCode());
        assertNull(result.paymentId());
    }

    @Test
    @DisplayName("4xx 본문에 코드가 없으면 상태 코드로 errorCode 를 만든다")
    void charge_clientErrorWithoutBody_returnsFailedWithHttpCode() {
        // given
        server.expect(requestTo(PAYMENTS_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        // when
        PaymentResult result = client.charge(request());

        // then
        assertEquals(PaymentStatus.FAILED, result.status());
        assertEquals("PAY_HTTP_401", result.errorCode());
    }

    @Test
    @DisplayName("5xx 는 처리됐는지 모르므로 UNKNOWN 이다")
    void charge_serverError_returnsUnknown() {
        // given
        server.expect(requestTo(PAYMENTS_URL)).andRespond(withServerError());

        // when
        PaymentResult result = client.charge(request());

        // then
        assertEquals(PaymentStatus.UNKNOWN, result.status());
        assertEquals("PAY_HTTP_500", result.errorCode());
        assertNull(result.paymentId());
    }

    @Test
    @DisplayName("연결이 끊기면 예외 대신 UNKNOWN 을 돌려준다")
    void charge_ioFailure_returnsUnknownInsteadOfThrowing() {
        // given
        server.expect(requestTo(PAYMENTS_URL)).andRespond(request -> {
            throw new IOException("connection reset");
        });

        // when
        PaymentResult result = client.charge(request());

        // then
        assertEquals(PaymentStatus.UNKNOWN, result.status());
        assertEquals(HttpPayClient.ERROR_UNREACHABLE, result.errorCode());
    }

    @Test
    @DisplayName("2xx 인데 본문이 JSON 이 아니면 UNKNOWN 이다")
    void charge_malformedSuccessBody_returnsUnknown() {
        // given
        server.expect(requestTo(PAYMENTS_URL))
                .andRespond(withSuccess("<html>gateway</html>", MediaType.TEXT_HTML));

        // when
        PaymentResult result = client.charge(request());

        // then
        assertEquals(PaymentStatus.UNKNOWN, result.status());
        assertEquals(HttpPayClient.ERROR_MALFORMED_BODY, result.errorCode());
    }

    @Test
    @DisplayName("모르는 status 값이면 UNKNOWN 으로 접고 paymentId 는 남긴다")
    void charge_unrecognizedStatus_returnsUnknownKeepingPaymentId() {
        // given
        server.expect(requestTo(PAYMENTS_URL)).andRespond(withSuccess("""
                {"paymentId":"pay-1","status":"SETTLED","amountMinor":92000,"currency":"KRW"}
                """, MediaType.APPLICATION_JSON));

        // when
        PaymentResult result = client.charge(request());

        // then
        assertEquals(PaymentStatus.UNKNOWN, result.status());
        assertEquals("pay-1", result.paymentId());
        assertEquals(HttpPayClient.ERROR_STATUS_UNRECOGNIZED, result.errorCode());
        assertFalse(result.isSuccess());
    }

    private static PaymentRequest request() {
        return new PaymentRequest("merchant-a", "checkout-1", "sha256:abc", 92000L, "KRW", "cred-1");
    }

    private static String successBody(String status) {
        return "{\"paymentId\":\"pay-1\",\"status\":\"" + status + "\",\"amountMinor\":92000,\"currency\":\"KRW\"}";
    }

    @Test
    @DisplayName("요청 컨텍스트에 requestId 가 있으면 X-Request-Id 로 그대로 나간다. Core→나→Pay 가 한 값으로 이어진다")
    void charge_withRequestContext_propagatesRequestId() {
        // given
        MDC.put(LogContext.REQUEST_ID, "core-req-42");
        server.expect(requestTo(PAYMENTS_URL))
                .andExpect(header(HttpPayClient.REQUEST_ID_HEADER, "core-req-42"))
                .andRespond(withSuccess("""
                        {"paymentId":"pay-1","status":"SUCCESS","amountMinor":92000,"currency":"KRW"}
                        """, MediaType.APPLICATION_JSON));

        try {
            // when
            client.charge(request());

            // then
            server.verify();
        } finally {
            MDC.clear();
        }
    }
}
