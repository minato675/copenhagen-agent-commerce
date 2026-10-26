package com.copenhagen.core.payment;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.copenhagen.core.checkout.MerchantCheckoutClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentClientContractTest {
    @Test
    void purchaseNotificationUsesPayServiceAuthenticationAndRequiredIdempotencyKey() {
        var builder = RestClient.builder().baseUrl("https://pay.example")
                .defaultHeader("Authorization", "Bearer test-service-token");
        var server = MockRestServiceServer.bindTo(builder).build();
        UUID purchaseId = UUID.randomUUID();
        String expectedKey = PaymentApprovalRequests.purchaseNotificationIdempotencyKey(purchaseId.toString());
        // Pay는 상태 변경 경계에서 UUID가 아닌 키를 400으로 거절한다(docs/api/pay-idempotency.md).
        assertEquals(expectedKey, UUID.fromString(expectedKey).toString());
        server.expect(org.springframework.test.web.client.ExpectedCount.twice(),
                        requestTo("https://pay.example/internal/v1/purchase-notifications"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-service-token"))
                .andExpect(header("Idempotency-Key", expectedKey))
                .andRespond(withSuccess());
        var client = new PaymentApprovalClient(builder.build());
        UUID userId = UUID.randomUUID();
        // 재시도가 같은 키를 재사용해야 Pay 알림이 1건으로 유지된다.
        client.notifyPurchase(userId, purchaseId);
        client.notifyPurchase(userId, purchaseId);
        server.verify();
    }

    @Test
    void recoveryReadsOrderAndPaymentFromFullMerchantCheckoutResponse() {
        var builder = RestClient.builder().baseUrl("https://merchant.example");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://merchant.example/ucp/v1/checkout-sessions/checkout-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"checkout-1","status":"completed","currency":"KRW","line_items":[],
                         "totals":[{"type":"total","amount":19600}],"expires_at":"2026-09-18T00:00:00Z",
                         "order":{"id":"order-1","permalink_url":"https://merchant.example/orders/1"},
                         "payment":{"paymentId":"payment-1","status":"SUCCESS"}}
                        """, MediaType.APPLICATION_JSON));
        var result = new MerchantCheckoutClient("merchant-a", builder.build()).current("checkout-1");
        assertEquals("order-1", result.order().id());
        assertEquals("payment-1", result.payment().paymentId());
        server.verify();
    }
}
