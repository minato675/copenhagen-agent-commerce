package com.copenhagen.core.orderwebhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.copenhagen.core.common.auth.CoreAuthenticationFilter;
import com.copenhagen.core.common.error.GlobalExceptionHandler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Platform Profile이 광고하는 v1 Order Webhook 계약. 실제 검증과 저장은 별도 서비스 테스트에서 확인한다. */
@WebMvcTest(
        controllers = OrderWebhookController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class OrderWebhookControllerTest {

    @MockitoBean
    private OrderWebhookService service;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Order Webhook을 수신하면 202 Accepted를 돌려주고 본문이 없다")
    void receive_returns202WithoutBody() throws Exception {
        mockMvc.perform(post("/webhooks/v1/ucp/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"o-1","status":"CONFIRMED"}"""))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드는 500이 아니라 405로 응답한다")
    void unsupportedMethod_returns405NotSwallowedAs500() throws Exception {
        mockMvc.perform(get("/webhooks/v1/ucp/orders"))
                .andExpect(status().isMethodNotAllowed());
    }
}
