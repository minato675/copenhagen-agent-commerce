package com.copenhagen.contract.internalapi;

import java.time.Instant;
import java.util.List;

/**
 * 승인 세션 상태. 승인 단위가 구매 요청으로 올라가면서(이슈 S15P21A207-486) 세션 1건이 판매처 N곳을
 * 갖는다. 판매처별 credential·결제 결과는 {@code items}에서 읽는다.
 *
 * <p>{@code credentialId}·{@code paymentId}·{@code paymentStatus}·{@code paymentLabel}은 단일 판매처
 * 시절의 필드로, 구 계약 제거 전까지만 남는다. 새 코드는 {@code items}만 본다.
 */
public record PaymentApprovalStatusResponse(
        String approvalId,
        ApprovalStatus approvalStatus,
        String paymentId,
        PaymentStatus paymentStatus,
        long amountMinor,
        String currency,
        String credentialId,
        Instant credentialExpiresAt,
        String paymentLabel,
        PaymentApprovalNextAction nextAction,
        List<PaymentApprovalItem> items) {

    public PaymentApprovalStatusResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 구 계약(단일 판매처) 호출부 호환. Pay가 {@code items}를 채우기 전까지 남는다. */
    public PaymentApprovalStatusResponse(
            String approvalId,
            ApprovalStatus approvalStatus,
            String paymentId,
            PaymentStatus paymentStatus,
            long amountMinor,
            String currency,
            String credentialId,
            Instant credentialExpiresAt,
            String paymentLabel,
            PaymentApprovalNextAction nextAction) {
        this(approvalId, approvalStatus, paymentId, paymentStatus, amountMinor, currency,
                credentialId, credentialExpiresAt, paymentLabel, nextAction, List.of());
    }
}
