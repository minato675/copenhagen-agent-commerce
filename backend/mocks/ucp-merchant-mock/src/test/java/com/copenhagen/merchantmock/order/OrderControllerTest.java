package com.copenhagen.merchantmock.order;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.order.OrderMessages.Created;
import com.copenhagen.merchantmock.order.OrderTestSupport.Harness;
import com.copenhagen.merchantmock.support.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.copenhagen.merchantmock.order.OrderTestSupport.CHECKOUT_ID;
import static com.copenhagen.merchantmock.order.OrderTestSupport.EXPECTED_UPDATED_AT;
import static com.copenhagen.merchantmock.order.OrderTestSupport.readyCheckout;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 조회의 HTTP 계약. 필드가 Core 확정본 4.10 의 다섯인지, 그 이상 늘지 않았는지가 핵심이다.
 */
class OrderControllerTest {

    private MockMvc mockMvc;
    private Harness harness;

    @BeforeEach
    void setUp() {
        this.harness = OrderTestSupport.harness();
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new OrderController(harness.service()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("조회는 200 이고 Core 확정본 4.10 모양이다")
    void get_existingOrder_returns200WithConfirmedShape() throws Exception {
        // given
        Created created = harness.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // when & then
        mockMvc.perform(get("/api/ucp/v1/merchant-a/orders/{orderId}", created.id()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.checkout_id").value(CHECKOUT_ID))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.fulfillment.status").value("CREATED"))
                .andExpect(jsonPath("$.updated_at").value(EXPECTED_UPDATED_AT))
                .andExpect(jsonPath("$.checkoutId").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("응답에 금액과 주문 항목을 싣지 않는다")
    void get_existingOrder_omitsAmountAndItems() throws Exception {
        // given
        Created created = harness.service().create(readyCheckout(), PaymentStatus.SUCCESS);

        // when & then
        mockMvc.perform(get("/api/ucp/v1/merchant-a/orders/{orderId}", created.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_amount").doesNotExist())
                .andExpect(jsonPath("$.currency").doesNotExist())
                .andExpect(jsonPath("$.line_items").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    @DisplayName("없는 주문은 404 ORDER_NOT_FOUND 다")
    void get_missingOrder_returns404() throws Exception {
        // when & then
        mockMvc.perform(get("/api/ucp/v1/merchant-a/orders/{orderId}", "order-없음"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.nextAction").value("CHECK_PURCHASE_STATUS"))
                .andExpect(jsonPath("$.message").isString());
    }
}
