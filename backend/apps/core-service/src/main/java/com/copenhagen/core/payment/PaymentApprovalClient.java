package com.copenhagen.core.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.payment.PaymentApprovalMessages.ApprovalCreatedResponse;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;

/**
 * Pay 내부 API 호출(이슈 410). 승인 세션 생성은 Core→Pay 인증(Idempotency-Key)만 있으면 되고, 상태 조회는
 * {@code core-service} 서비스 토큰이 필요하다 — 둘 다 {@link PaymentApprovalConfiguration}이 만든
 * {@link RestClient}가 {@code Authorization} 헤더를 이미 싣고 있다.
 */
public class PaymentApprovalClient {

    public void notifyPurchase(java.util.UUID userId, java.util.UUID purchaseId) {
        restClient.post().uri("/internal/v1/purchase-notifications")
                .header(IDEMPOTENCY_KEY_HEADER,
                        PaymentApprovalRequests.purchaseNotificationIdempotencyKey(purchaseId.toString()))
                .body(java.util.Map.of("userId", userId, "purchaseRequestId", purchaseId))
                .retrieve().toBodilessEntity();
    }

    public java.util.List<com.copenhagen.contract.internalapi.PurchaseNotificationStatus> notificationStatus(
            java.util.UUID userId, java.util.UUID purchaseId) {
        var statuses = restClient.get()
                .uri("/internal/v1/purchase-notifications/{id}?userId={userId}", purchaseId, userId)
                .retrieve().body(com.copenhagen.contract.internalapi.PurchaseNotificationStatus[].class);
        return statuses == null ? java.util.List.of() : java.util.List.of(statuses);
    }

    public void resendNotification(java.util.UUID userId, java.util.UUID purchaseId, java.util.UUID key) {
        restClient.post().uri("/internal/v1/purchase-notifications/{id}/resend?userId={userId}", purchaseId, userId)
                .header(IDEMPOTENCY_KEY_HEADER, key.toString()).retrieve().toBodilessEntity();
    }

    private static final String CREATE_PATH = "/internal/v1/payment-approvals";
    private static final String STATUS_PATH = "/internal/v1/payment-approvals/{approvalId}/status";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalClient.class);

    private final RestClient restClient;

    public PaymentApprovalClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public ApprovalCreatedResponse create(CreateApprovalRequest request, String idempotencyKey) {
        try {
            ApprovalCreatedResponse response = restClient.post()
                    .uri(CREATE_PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(request)
                    .retrieve()
                    .body(ApprovalCreatedResponse.class);
            if (response == null || response.approvalId() == null || response.approvalId().isBlank()
                    || response.expiresAt() == null) {
                throw new CoreException(CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE);
            }
            return response;
        } catch (RestClientException exception) {
            log.warn("Pay 승인 세션 생성 실패: purchaseRequestId={}, items={}, error={}",
                    request.purchaseRequestId(), request.items().size(), exception.getClass().getSimpleName());
            throw new CoreException(
                    CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE,
                    CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE.defaultMessage(),
                    null,
                    CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE.defaultNextAction(),
                    exception);
        }
    }

    /** 폴링 워커 전용. 실패는 호출자가 다음 tick 재시도로 처리하므로 그대로 던진다. */
    public PaymentApprovalStatusResponse status(String approvalId) {
        return restClient.get()
                .uri(STATUS_PATH, approvalId)
                .retrieve()
                .body(PaymentApprovalStatusResponse.class);
    }
}
