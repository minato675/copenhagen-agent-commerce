package com.copenhagen.merchantmock.checkout;

import java.util.ArrayList;
import java.util.List;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.CompleteResult;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.OrderRef;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.PaymentRef;
import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 체크아웃 다섯 엔드포인트의 HTTP 계약. 필드 이름이 Core 확정본과 같은 snake_case 인지가 핵심이다. */
class CheckoutControllerTest {

    private static final String CREATE_BODY = """
            {
              "line_items": [
                { "product_id": "product-101", "option_value_ids": ["product-101-270"], "quantity": 1 }
              ],
              "fulfillment": {
                "type": "shipping",
                "destination": {
                  "recipient": "홍길동",
                  "postal_code": "06236",
                  "address_line1": "서울특별시 강남구 테헤란로 1",
                  "address_line2": "101호"
                }
              },
              "purchase_request_id": "pr-0001"
            }
            """;

    /** Core 확정본 4.8 그대로. credentialId 하나뿐이다. */
    private static final String COMPLETE_BODY = """
            { "payment": { "instrument": { "credential": { "credentialId": "credential-123" } } } }
            """;

    private static final String NO_ADDRESS_BODY = """
            {
              "line_items": [
                { "product_id": "product-101", "option_value_ids": ["product-101-270"], "quantity": 1 }
              ]
            }
            """;

    private MockMvc mockMvc;
    private Harness harness;
    private final List<String> completeCalls = new ArrayList<>();
    private CompleteResult completeResult;
    private ApiException completeFailure;

    @BeforeEach
    void setUp() {
        this.harness = CheckoutTestSupport.harness();
        // Complete 의 흐름은 CheckoutCompletionServiceTest 몫이다. 여기서는 HTTP 계약만 보므로 기록만 한다.
        CheckoutCompletionService completion = new CheckoutCompletionService(null, null, null, null, null, null,
                null, null, null) {
            @Override
            public CompleteResult complete(String merchantId, String checkoutId, String credentialId) {
                completeCalls.add(merchantId + ":" + checkoutId + ":" + credentialId);
                if (completeFailure != null) {
                    throw completeFailure;
                }
                return completeResult;
            }
        };
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new CheckoutController(harness.service(), completion))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("ready_for_complete 응답에 merchant_authorization 이 Compact JWS 로 실린다")
    void create_ready_carriesMerchantAuthorization() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions")
                        .header("Idempotency-Key", "checkout-create-signed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ready_for_complete"))
                .andExpect(jsonPath("$.merchant_authorization").isString())
                .andExpect(jsonPath("$.merchant_authorization").value(
                        org.hamcrest.Matchers.matchesRegex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")))
                .andExpect(jsonPath("$.order").doesNotExist())
                .andExpect(jsonPath("$.payment").doesNotExist());
    }

    @Test
    @DisplayName("purchase_request_id 가 없으면 incomplete 이고 messages 가 그 필드를 가리킨다")
    void create_withoutPurchaseRequestId_returnsIncomplete() throws Exception {
        // given
        String body = CREATE_BODY.replace("\"pr-0001\"", "null");

        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("incomplete"))
                .andExpect(jsonPath("$.messages[0].code").value("CHECKOUT_INCOMPLETE"))
                .andExpect(jsonPath("$.messages[0].target").value("purchase_request_id"))
                .andExpect(jsonPath("$.merchant_authorization").doesNotExist());
    }

    @Test
    @DisplayName("완료는 200 이고 응답이 Core 확정본 4.8 모양이다")
    void complete_success_returns200WithOrderAndPayment() throws Exception {
        // given
        completeResult = new CompleteResult("checkout-1", "completed",
                new OrderRef("order-999", "http://ucp-merchant-mock:8080/orders/order-999"),
                new PaymentRef("payment-777", "SUCCESS", null));

        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions/{id}/complete", "checkout-1")
                        .header("Idempotency-Key", "checkout-complete-checkout-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("checkout-1"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.order.id").value("order-999"))
                .andExpect(jsonPath("$.order.permalink_url").value("http://ucp-merchant-mock:8080/orders/order-999"))
                .andExpect(jsonPath("$.payment.paymentId").value("payment-777"))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.payment.errorCode").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("merchant-a:checkout-1:credential-123"), completeCalls);
    }

    @Test
    @DisplayName("결제 실패도 200 이고 status 가 ready_for_complete 다. order 키는 없다")
    void complete_failed_returns200WithoutOrder() throws Exception {
        // given
        completeResult = new CompleteResult("checkout-1", "ready_for_complete", null,
                new PaymentRef("payment-777", "FAILED", "PG_DECLINED"));

        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions/{id}/complete", "checkout-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready_for_complete"))
                .andExpect(jsonPath("$.order").doesNotExist())
                .andExpect(jsonPath("$.payment.status").value("FAILED"))
                .andExpect(jsonPath("$.payment.errorCode").value("PG_DECLINED"));
    }

    @Test
    @DisplayName("credentialId 가 없는 본문은 서비스에 null 로 넘어간다. 검증은 서비스가 한다")
    void complete_bodyWithoutCredential_passesNull() throws Exception {
        // given
        completeFailure = ApiException.invalidRequest("credentialId 가 필요하다");

        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions/{id}/complete", "checkout-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.nextAction").value(org.hamcrest.Matchers.nullValue()));
        org.junit.jupiter.api.Assertions.assertEquals(List.of("merchant-a:checkout-1:null"), completeCalls);
    }

    @Test
    @DisplayName("상태 거절은 409 오류 Envelope 이다")
    void complete_rejected_returns409Envelope() throws Exception {
        // given
        completeFailure = ApiException.checkoutExpired("checkout-1");

        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions/{id}/complete", "checkout-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_EXPIRED"))
                .andExpect(jsonPath("$.currentState").value("ready_for_complete"))
                .andExpect(jsonPath("$.nextAction").value("RESTART_CHECKOUT"));
    }

    @Test
    @DisplayName("생성은 201 이고 Core 확정본 모양이다")
    void create_validRequest_returns201WithSnakeCaseBody() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions")
                        .header("Idempotency-Key", "checkout-create-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("ready_for_complete"))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.line_items[0].id").value("line-1"))
                .andExpect(jsonPath("$.line_items[0].item.id").value("product-101"))
                .andExpect(jsonPath("$.line_items[0].item.title").value("러닝화 A"))
                .andExpect(jsonPath("$.line_items[0].item.price").value(89000))
                .andExpect(jsonPath("$.line_items[0].quantity").value(1))
                .andExpect(jsonPath("$.fulfillment.type").value("shipping"))
                .andExpect(jsonPath("$.fulfillment.status").value("available"))
                .andExpect(jsonPath("$.fulfillment.amount").value(3000))
                .andExpect(jsonPath("$.totals[0].type").value("subtotal"))
                .andExpect(jsonPath("$.totals[0].amount").value(89000))
                .andExpect(jsonPath("$.totals[1].type").value("fulfillment"))
                .andExpect(jsonPath("$.totals[2].type").value("total"))
                .andExpect(jsonPath("$.totals[2].amount").value(92000))
                .andExpect(jsonPath("$.expires_at").value(CheckoutTestSupport.EXPECTED_EXPIRES_AT))
                .andExpect(jsonPath("$.messages").doesNotExist())
                .andExpect(jsonPath("$.lineItems").doesNotExist());
    }

    @Test
    @DisplayName("배송지 없는 생성은 201 incomplete 와 messages 다")
    void create_noAddress_returns201Incomplete() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NO_ADDRESS_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("incomplete"))
                .andExpect(jsonPath("$.messages[0].code").value("ADDRESS_REQUIRED"))
                .andExpect(jsonPath("$.messages[0].severity").value("error"))
                .andExpect(jsonPath("$.messages[0].target").value("fulfillment.destination"))
                .andExpect(jsonPath("$.fulfillment").doesNotExist())
                .andExpect(jsonPath("$.expires_at").doesNotExist());
    }

    @Test
    @DisplayName("같은 멱등 키로 두 번 보내면 같은 id 다")
    void create_sameIdempotencyKey_returnsSameId() throws Exception {
        // when & then
        String first = createAndReadId("checkout-create-same");
        String second = createAndReadId("checkout-create-same");

        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertEquals(1, harness.repository().size());
    }

    @Test
    @DisplayName("조회는 생성 응답과 같은 스냅샷이다")
    void get_existingCheckout_returnsSameSnapshot() throws Exception {
        // given & when
        String id = createAndReadId("checkout-create-get");

        // then
        mockMvc.perform(get("/api/ucp/v1/merchant-a/checkout-sessions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ready_for_complete"))
                .andExpect(jsonPath("$.totals[2].amount").value(92000))
                .andExpect(jsonPath("$.expires_at").value(CheckoutTestSupport.EXPECTED_EXPIRES_AT));
    }

    @Test
    @DisplayName("없는 체크아웃 조회는 404 오류 Envelope 이다")
    void get_unknownId_returns404WithErrorEnvelope() throws Exception {
        // when & then
        mockMvc.perform(get("/api/ucp/v1/merchant-a/checkout-sessions/{id}", "nope")
                        .header("X-Request-Id", "req-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_NOT_FOUND"))
                .andExpect(jsonPath("$.nextAction").value("RETURN_TO_CART"));
    }

    @Test
    @DisplayName("수정은 200 이고 배송지를 채우면 ready_for_complete 다")
    void update_addressAdded_returns200ReadyForComplete() throws Exception {
        // given & when
        MvcResult created = mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NO_ADDRESS_BODY))
                .andExpect(jsonPath("$.status").value("incomplete"))
                .andReturn();
        String id = readId(created);

        // then
        mockMvc.perform(put("/api/ucp/v1/merchant-a/checkout-sessions/{id}", id)
                        .header("Idempotency-Key", "checkout-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ready_for_complete"))
                .andExpect(jsonPath("$.fulfillment.amount").value(3000))
                .andExpect(jsonPath("$.messages").doesNotExist());
    }

    @Test
    @DisplayName("없는 상품으로 생성하면 404 PRODUCT_NOT_FOUND 다")
    void create_unknownProduct_returns404() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("product-101\"", "product-999\"")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 체크아웃 수정은 404 다")
    void update_unknownId_returns404() throws Exception {
        // when & then
        mockMvc.perform(put("/api/ucp/v1/merchant-a/checkout-sessions/{id}", "nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_NOT_FOUND"));
    }

    @Test
    @DisplayName("취소는 200 이고 두 필드만 돌려준다")
    void cancel_existingCheckout_returns200WithTwoFields() throws Exception {
        // given & when
        String id = createAndReadId("checkout-create-1");

        // then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("canceled"))
                .andExpect(jsonPath("$.totals").doesNotExist())
                .andExpect(jsonPath("$.line_items").doesNotExist());

        mockMvc.perform(get("/api/ucp/v1/merchant-a/checkout-sessions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("canceled"));
    }

    @Test
    @DisplayName("없는 체크아웃 취소는 404 다")
    void cancel_unknownId_returns404() throws Exception {
        // when & then
        mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions/{id}/cancel", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_NOT_FOUND"))
                .andExpect(jsonPath("$.nextAction").value("RETURN_TO_CART"));
    }

    private String createAndReadId(String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ucp/v1/merchant-a/checkout-sessions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    private String readId(MvcResult result) throws Exception {
        return harness.objectMapper()
                .readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("id").asString();
    }
}
