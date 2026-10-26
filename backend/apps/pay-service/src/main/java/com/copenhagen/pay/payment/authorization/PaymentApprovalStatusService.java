package com.copenhagen.pay.payment.authorization;

import com.copenhagen.contract.internalapi.ApprovalStatus;
import com.copenhagen.contract.internalapi.PaymentApprovalItem;
import com.copenhagen.contract.internalapi.PaymentApprovalNextAction;
import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.validation.RequestValidation;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentApprovalStatusService {

    private static final int APPROVAL_ID_MAX_LENGTH = 36;

    private final PaymentApprovalStatusRepository repository;

    public PaymentApprovalStatusService(PaymentApprovalStatusRepository repository) {
        this.repository = repository;
    }

    public PaymentApprovalStatusResponse find(String approvalId) {
        String normalizedApprovalId = normalizeApprovalId(approvalId);
        PaymentApprovalStatusRepository.PaymentApprovalStatusView view = repository
                .findByApprovalId(normalizedApprovalId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));

        List<PaymentApprovalItem> items = view.items().stream()
                .map(PaymentApprovalStatusService::toItem)
                .toList();
        // 평면 필드는 구 계약 호환용이며 첫 판매처 값을 담는다. 판매처가 여러 곳인 구매는
        // items 를 봐야 한다.
        PaymentApprovalStatusRepository.ItemView primary = view.items().isEmpty()
                ? null
                : view.items().getFirst();

        return new PaymentApprovalStatusResponse(
                view.approvalId(),
                ApprovalStatus.valueOf(view.approvalStatus().name()),
                primary == null ? null : primary.paymentId(),
                primary == null || primary.paymentStatus() == null
                        ? null
                        : com.copenhagen.contract.internalapi.PaymentStatus.valueOf(
                                primary.paymentStatus().name()),
                view.totalAmountMinor(),
                view.currency(),
                primary == null ? null : primary.credentialId(),
                primary == null ? null : primary.credentialExpiresAt(),
                primary == null ? null : primary.paymentLabel(),
                nextAction(view),
                items);
    }

    private static PaymentApprovalItem toItem(
            PaymentApprovalStatusRepository.ItemView item) {
        return new PaymentApprovalItem(
                item.merchantId(),
                item.checkoutId(),
                item.credentialId(),
                item.credentialExpiresAt(),
                item.paymentId(),
                item.paymentStatus() == null
                        ? null
                        : com.copenhagen.contract.internalapi.PaymentStatus.valueOf(
                                item.paymentStatus().name()),
                item.paymentLabel());
    }

    /**
     * 판매처가 여러 곳이면 가장 덜 진행된 판매처를 기준으로 삼는다. 하나라도 결과가 남아
     * 있으면 Core 가 계속 폴링해야 하기 때문이다.
     */
    private PaymentApprovalNextAction nextAction(
            PaymentApprovalStatusRepository.PaymentApprovalStatusView view) {
        boolean approved = view.approvalStatus() == AuthorizationSessionStatus.APPROVED;
        boolean anyPaymentMissing = view.items().isEmpty()
                || view.items().stream().anyMatch(item -> item.paymentStatus() == null);
        if (anyPaymentMissing) {
            return approved ? PaymentApprovalNextAction.WAIT_FOR_PAYMENT_CREATION : null;
        }
        boolean anyInFlight = view.items().stream().anyMatch(item -> switch (item.paymentStatus()) {
            case READY, PROCESSING, UNKNOWN -> true;
            case SUCCESS, FAILED -> false;
        });
        return anyInFlight ? PaymentApprovalNextAction.WAIT_FOR_PAYMENT_STATUS : null;
    }

    private String normalizeApprovalId(String approvalId) {
        String validatedApprovalId = RequestValidation.requireLengthAtMost(
                approvalId,
                "approvalId",
                APPROVAL_ID_MAX_LENGTH);
        try {
            UUID uuid = UUID.fromString(validatedApprovalId);
            if (!uuid.toString().equalsIgnoreCase(validatedApprovalId)) {
                throw invalidApprovalId();
            }
            return uuid.toString();
        } catch (IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "approvalId 형식이 올바르지 않습니다.",
                    null,
                    "CHECK_APPROVAL_ID",
                    exception);
        }
    }

    private PayException invalidApprovalId() {
        return new PayException(
                PayErrorCode.INVALID_REQUEST,
                "approvalId 형식이 올바르지 않습니다.",
                null,
                "CHECK_APPROVAL_ID");
    }
}
