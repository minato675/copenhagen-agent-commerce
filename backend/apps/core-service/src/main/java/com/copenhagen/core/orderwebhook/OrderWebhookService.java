package com.copenhagen.core.orderwebhook;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import com.copenhagen.contract.ucp.OrderWebhookPayload;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.AwaitingApprovalCheckout;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.payment.PaymentCompletionService;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.product.MerchantsProperties.MerchantConfig;
import com.copenhagen.core.ucp.MerchantProfileDirectory;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderWebhookService {

    private static final int MAX_BODY_BYTES = 65536;
    private static final Set<String> STATUSES = Set.of("CREATED", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELED");

    private final ObjectMapper mapper;
    private final MerchantsProperties merchants;
    private final MerchantProfileDirectory directory;
    private final OrderWebhookSignature signature;
    private final MerchantCheckoutRepository checkouts;
    private final PaymentCompletionService completion;
    private final OrderWebhookRepository repository;
    private final Clock clock;

    public OrderWebhookService(ObjectMapper mapper, MerchantsProperties merchants, MerchantProfileDirectory directory,
            OrderWebhookSignature signature, MerchantCheckoutRepository checkouts, PaymentCompletionService completion,
            OrderWebhookRepository repository, Clock clock) {
        this.mapper = mapper;
        this.merchants = merchants;
        this.directory = directory;
        this.signature = signature;
        this.checkouts = checkouts;
        this.completion = completion;
        this.repository = repository;
        this.clock = clock;
    }

    public void receive(byte[] body, HttpHeaders headers) {
        if (headers.getFirst("Signature") == null || headers.getFirst("Signature-Input") == null
                || headers.getFirst("Content-Digest") == null) {
            throw new CoreException(CoreErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        String webhookId = headers.getFirst("Webhook-Id");
        if (body.length > MAX_BODY_BYTES || webhookId == null || !webhookId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new CoreException(CoreErrorCode.INVALID_REQUEST);
        }
        OrderWebhookPayload payload;
        Instant updatedAt;
        try {
            payload = mapper.readValue(body, OrderWebhookPayload.class);
            if (payload.order() == null || !validId(payload.order().id())
                    || !validId(payload.order().checkout_id()) || !STATUSES.contains(payload.order().status())) {
                throw new CoreException(CoreErrorCode.INVALID_REQUEST);
            }
            updatedAt = OffsetDateTime.parse(payload.order().updated_at()).toInstant();
        } catch (RuntimeException exception) {
            throw new CoreException(CoreErrorCode.INVALID_REQUEST);
        }
        MerchantConfig merchant = merchants.merchants().stream()
                .filter(config -> config.id().equals(payload.merchant_id())).findFirst()
                .orElseThrow(() -> new CoreException(CoreErrorCode.WEBHOOK_SIGNATURE_INVALID));
        // UCP-Agent의 임의 URL은 사용하지 않는다. 등록된 Merchant Profile의 공개키만 신뢰한다.
        String digest = signature.verify(body, headers, directory.signingKeys(merchant));
        if (repository.processed(merchant.refId(), webhookId, digest)) {
            return;
        }
        AwaitingApprovalCheckout checkout = checkouts
                .findByMerchantCheckout(merchant.refId(), payload.order().checkout_id())
                .orElseThrow(() -> new CoreException(CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE));
        if (checkout.paymentApprovalId() == null
                || completion.synchronize(checkout, null) != PaymentCompletionService.Outcome.COMPLETED) {
            // 아직 복구할 수 없는 주문은 2xx로 버리지 않고 Merchant outbox의 재전송을 받는다.
            throw new CoreException(CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE);
        }
        String status = "CANCELED".equals(payload.order().status()) ? "CANCELLED" : payload.order().status();
        repository.apply(merchant.refId(), checkout.checkoutSnapshotId(), payload.order().id(), webhookId, digest,
                status, updatedAt, clock.instant());
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_:-]{1,255}");
    }
}
