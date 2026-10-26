package com.copenhagen.core.orderwebhook;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.AwaitingApprovalCheckout;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.payment.PaymentCompletionService;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.product.MerchantsProperties.MerchantConfig;
import com.copenhagen.core.ucp.CoreUcpProperties;
import com.copenhagen.core.ucp.MerchantProfileDirectory;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import tools.jackson.databind.ObjectMapper;

import static com.copenhagen.core.orderwebhook.OrderWebhookSignatureTest.NOW;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderWebhookServiceTest {

    @Test
    void signedMerchantEvent_recoversCompletionBeforeProjectingOrderAndRejectsUnsignedEvent() throws Exception {
        MerchantConfig merchant = new MerchantConfig("merchant-a", 1L, "몰", URI.create("http://merchant:8080"),
                Duration.ofSeconds(1), Duration.ofSeconds(3));
        MerchantProfileDirectory directory = mock(MerchantProfileDirectory.class);
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("merchant-key").generate();
        when(directory.signingKeys(merchant)).thenReturn(List.of(key.toPublicJWK()));
        MerchantCheckoutRepository checkouts = mock(MerchantCheckoutRepository.class);
        AwaitingApprovalCheckout checkout = new AwaitingApprovalCheckout(
                1L, 2L, 3L, 1L, "checkout-1", "approval-1", NOW.plusSeconds(60), 4L, 1000L, "KRW");
        when(checkouts.findByMerchantCheckout(1L, "checkout-1")).thenReturn(Optional.of(checkout));
        PaymentCompletionService completion = mock(PaymentCompletionService.class);
        when(completion.synchronize(checkout, null)).thenReturn(PaymentCompletionService.Outcome.COMPLETED);
        OrderWebhookRepository repository = mock(OrderWebhookRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OrderWebhookService service = new OrderWebhookService(new ObjectMapper(),
                new MerchantsProperties(List.of(merchant)), directory,
                new OrderWebhookSignature(new CoreUcpProperties(null, null), clock),
                checkouts, completion, repository, clock);
        byte[] payload = """
                {"merchant_id":"merchant-a","order":{"id":"order-1","checkout_id":"checkout-1",
                "status":"CANCELED","fulfillment":{"status":"CANCELED"},"updated_at":"2026-09-18T00:00:00Z"}}
                """.getBytes(StandardCharsets.UTF_8);
        HttpHeaders unsigned = new HttpHeaders();
        unsigned.set("Webhook-Id", "event-1");
        assertThrows(CoreException.class, () -> service.receive(payload, unsigned));
        verify(repository, never()).apply(anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
        HttpHeaders signed = OrderWebhookSignatureTest.signed(payload, key, NOW);
        service.receive(payload, signed);
        verify(repository).apply(1L, 4L, "order-1", "event-1", signed.getFirst("Content-Digest"),
                "CANCELLED", NOW, NOW);
        when(completion.synchronize(checkout, null)).thenReturn(PaymentCompletionService.Outcome.IN_PROGRESS);
        assertThrows(CoreException.class, () -> service.receive(payload, signed));
    }
}
