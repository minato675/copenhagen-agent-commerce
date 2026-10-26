package com.copenhagen.core.payment;

import java.time.Instant;
import java.util.List;

/**
 * {@code POST /internal/v1/payment-approvals}(pay-service {@code AuthorizationSessionController})
 * 계약. 필드는 그 컨트롤러의 {@code CreateAuthorizationSessionRequest}/{@code ApprovalCreatedResponse}와
 * 1:1로 대응한다(이슈 410, technical-flow.md §5).
 *
 * <p>이슈 S15P21A207-486에서 승인 단위가 checkout에서 구매 요청으로 올라갔다. 한 구매 요청의 판매처
 * 전부를 {@code items}에 담아 한 번만 호출한다. 이 DTO는 Core 내부 정의이고 Pay의 요청 DTO와
 * 이원화돼 있으므로, 계약을 바꿀 때 양쪽을 함께 고쳐야 한다.
 */
public final class PaymentApprovalMessages {

    private PaymentApprovalMessages() {
    }

    public record CreateApprovalRequest(
            String purchaseRequestId,
            String userId,
            long totalAmountMinor,
            String currency,
            List<ApprovalItem> items) {

        public CreateApprovalRequest {
            items = List.copyOf(items);
        }
    }

    public record ApprovalItem(
            String merchantId,
            String checkoutId,
            String checkoutHash,
            String checkoutJwt,
            DisplaySnapshot display,
            long amountMinor,
            String currency) {
    }

    public record DisplaySnapshot(String merchantName, List<SummaryItem> summary) {
    }

    public record SummaryItem(String name, String option, int quantity, long amountMinor) {
    }

    public record ApprovalCreatedResponse(
            String approvalId,
            String status,
            String requiredAction,
            Instant expiresAt,
            String nextAction,
            List<CreatedItem> items) {

        public ApprovalCreatedResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** 생성 응답의 에코. Core가 어느 {@code merchant_checkouts} 행에 승인 ID를 달지 이 값으로 맞춘다. */
    public record CreatedItem(String merchantId, String checkoutId) {
    }
}
