package com.copenhagen.core.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.CreatingApprovalItem;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.CreatingApprovalPurchase;
import com.copenhagen.core.payment.PaymentApprovalMessages.ApprovalCreatedResponse;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;
import com.copenhagen.core.payment.PaymentApprovalRequests.ApprovalItemInput;
import com.copenhagen.core.product.MerchantsProperties;
import tools.jackson.databind.ObjectMapper;

/**
 * 저장된 승인 요청을 재전송한다. HTTP 응답 유실도 Pay의 같은 멱등키로 복구한다.
 *
 * <p>승인이 구매 요청당 1건이므로(이슈 S15P21A207-486) 재시도 단위도 구매 요청이다. 한 구매 요청의
 * checkout이 전부 {@code CREATING}일 때만 대상이 된다 — 일부가 이미 승인을 받았다면 재생성이 아니라
 * 그 승인을 따라가야 한다.
 */
@Component
@ConditionalOnProperty(prefix = "core.payment.worker", name = "enabled", havingValue = "true")
public class PaymentApprovalCreationWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalCreationWorker.class);

    private final MerchantCheckoutRepository repository;
    private final PaymentApprovalClient client;
    private final CartMerchants merchants;
    private final MerchantsProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PaymentApprovalCreationWorker(MerchantCheckoutRepository repository, PaymentApprovalClient client,
            CartMerchants merchants, MerchantsProperties properties, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.client = client;
        this.merchants = merchants;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${core.payment.worker.poll-interval-seconds:3}000",
            initialDelayString = "${core.payment.worker.poll-interval-seconds:3}000")
    public void retryPending() {
        for (CreatingApprovalPurchase row : repository.findCreatingApprovals(clock.instant())) {
            try {
                retry(row);
            } catch (RuntimeException exception) {
                // 요청 본문에는 Merchant Authorization이 있으므로 예외 원문이나 본문은 남기지 않는다.
                log.warn("payment.approval.retry.failed purchaseRequestId={}, error={}",
                        row.id(), exception.getClass().getSimpleName());
            }
        }
    }

    private void retry(CreatingApprovalPurchase row) {
        Instant now = clock.instant();
        if (!repository.claimApprovalCreation(row.id(), now)) {
            return;
        }
        String requestJson = row.approvalRequest();
        if (requestJson == null) {
            CreateApprovalRequest reconstructed = PaymentApprovalRequests.create(
                    row.publicId(), row.userId(), row.items().stream().map(this::toInput).toList());
            requestJson = repository.saveApprovalRequestIfAbsent(row.id(), mapper.writeValueAsString(reconstructed));
        }
        CreateApprovalRequest request = mapper.readValue(requestJson, CreateApprovalRequest.class);
        try {
            // 만료 뒤에도 최초 응답이 유실된 승인 세션은 같은 키로 먼저 복구한다.
            ApprovalCreatedResponse response = client.create(request,
                    PaymentApprovalRequests.idempotencyKey(request.purchaseRequestId()));
            for (CreatingApprovalItem item : row.items()) {
                repository.markAwaitingApproval(
                        item.checkoutId(), response.approvalId(), response.expiresAt(), clock.instant());
            }
        } catch (com.copenhagen.core.common.error.CoreException exception) {
            if (row.expiresAt() != null && !now.isBefore(row.expiresAt())
                    && exception.getCause() instanceof org.springframework.web.client.HttpClientErrorException http
                    && (http.getStatusCode().value() == 400 || http.getStatusCode().value() == 422)) {
                expireAll(row.items(), now);
                return;
            }
            throw exception;
        }
    }

    private ApprovalItemInput toInput(CreatingApprovalItem item) {
        String merchantId = merchants.merchantId(item.merchantRefId());
        return new ApprovalItemInput(merchantId, displayName(merchantId), item.checkoutDigest(),
                mapper.readValue(item.checkoutPayload(), Checkout.class));
    }

    private String displayName(String merchantId) {
        return properties.merchants().stream().filter(config -> config.id().equals(merchantId))
                .map(MerchantsProperties.MerchantConfig::displayName).findFirst().orElse(merchantId);
    }

    private void expireAll(List<CreatingApprovalItem> items, Instant now) {
        for (CreatingApprovalItem item : items) {
            repository.markExpired(item.checkoutId(), now);
        }
    }
}
