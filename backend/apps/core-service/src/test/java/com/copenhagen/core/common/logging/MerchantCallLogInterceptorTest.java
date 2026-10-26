package com.copenhagen.core.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MerchantCallLogInterceptorTest {

    private final RestClient.Builder builder = RestClient.builder()
            .requestInterceptor(new MerchantCallLogInterceptor("merchant-a"));
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient restClient = builder.build();

    @AfterEach
    void clearContext() {
        CoreLogContext.clear();
    }

    @Test
    @DisplayName("요청의 X-Request-Id를 그대로 실어 보낸다 — Merchant 로그와 같은 값으로 이어진다")
    void intercept_propagatesRequestId() {
        CoreLogContext.put(CoreLogContext.REQUEST_ID, "core-req-42");
        server.expect(requestTo("/ucp/v1/catalog/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(CoreRequestIdFilter.HEADER, "core-req-42"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        restClient.post().uri("/ucp/v1/catalog/search").body("{}").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("요청 컨텍스트가 없어도 새 값을 만들어 보낸다 — 헤더 없이 나가지 않는다")
    void intercept_withoutContext_stillSendsHeader() {
        server.expect(requestTo("/ucp/v1/catalog/search"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst(CoreRequestIdFilter.HEADER))
                        .isNotBlank())
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        restClient.post().uri("/ucp/v1/catalog/search").body("{}").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    @DisplayName("Platform Profile URI가 있으면 UCP-Agent 헤더로 함께 전달한다")
    void intercept_withPlatformProfile_sendsUcpAgent() {
        RestClient.Builder profileBuilder = RestClient.builder()
                .requestInterceptor(new MerchantCallLogInterceptor("merchant-a", "https://core.example/.well-known/ucp"));
        MockRestServiceServer profileServer = MockRestServiceServer.bindTo(profileBuilder).build();
        RestClient profileClient = profileBuilder.build();
        profileServer.expect(requestTo("/catalog/search"))
                .andExpect(header("UCP-Agent", "profile=\"https://core.example/.well-known/ucp\""))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        profileClient.post().uri("/catalog/search").body("{}").retrieve().toBodilessEntity();

        profileServer.verify();
    }

    @Test
    @DisplayName("호출 결과를 target·method·path·status와 함께 한 줄 남긴다")
    void intercept_success_writesLine() {
        server.expect(requestTo("/ucp/v1/catalog/product"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            restClient.post().uri("/ucp/v1/catalog/product").body("{}").retrieve().toBodilessEntity();

            assertThat(recorder.singleLine())
                    .contains("\"event\":\"http.client\"")
                    .contains("\"target\":\"merchant-a\"")
                    .contains("\"method\":\"POST\"")
                    .contains("\"path\":\"/ucp/v1/catalog/product\"")
                    .contains("\"status\":200")
                    .contains("\"durationMs\":");
        }
    }

    @Test
    @DisplayName("5xx도 응답이므로 status로 남는다 — 예외 필드는 쓰지 않는다")
    void intercept_serverError_logsStatus() {
        server.expect(requestTo("/ucp/v1/catalog/search")).andRespond(withServerError());

        try (AccessLogRecorder recorder = AccessLogRecorder.attach()) {
            assertThatThrownBy(() -> restClient.post()
                    .uri("/ucp/v1/catalog/search")
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity())
                    .isInstanceOf(RestClientException.class);

            assertThat(recorder.singleLine())
                    .contains("\"status\":500")
                    .doesNotContain("\"error\"");
        }
    }
}
