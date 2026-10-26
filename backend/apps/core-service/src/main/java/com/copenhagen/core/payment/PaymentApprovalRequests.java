package com.copenhagen.core.payment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.payment.PaymentApprovalMessages.ApprovalItem;
import com.copenhagen.core.payment.PaymentApprovalMessages.CreateApprovalRequest;
import com.copenhagen.core.payment.PaymentApprovalMessages.DisplaySnapshot;
import com.copenhagen.core.payment.PaymentApprovalMessages.SummaryItem;

/** 최초 호출과 장애 복구가 같은 승인 대상과 멱등키를 사용한다. */
public final class PaymentApprovalRequests {

    private PaymentApprovalRequests() {
    }

    /**
     * Pay는 상태 변경 경계에서 UUID가 아닌 키를 거절하므로 이름 기반 UUID로 만든다(pay-idempotency.md).
     *
     * <p>승인이 구매 요청당 1건이므로(이슈 S15P21A207-486) 키도 구매 요청만으로 결정된다. 판매처가
     * 섞여 들어가면 재시도가 같은 키를 못 만들어 승인 세션이 중복 생성된다.
     */
    public static String idempotencyKey(String purchaseRequestId) {
        return UUID.nameUUIDFromBytes(
                ("copenhagen-pay-approval:" + purchaseRequestId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /** 구매 요청별로 고정된 키. 재시도·재전송이 같은 값을 써야 인계 알림이 1건으로 유지된다. */
    public static String purchaseNotificationIdempotencyKey(String purchaseRequestId) {
        return UUID.nameUUIDFromBytes(
                ("copenhagen-pay-purchase-handoff:" + purchaseRequestId)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /**
     * 한 구매 요청의 판매처 전부를 담은 승인 생성 요청. 통화는 판매처마다 확정되지만 Pay가 혼합을
     * 거절하므로(결정 3) 대표 통화는 첫 항목 값을 쓴다.
     */
    public static CreateApprovalRequest create(
            String purchaseRequestId, String userId, List<ApprovalItemInput> inputs) {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("승인 대상 checkout이 없습니다.");
        }
        List<ApprovalItem> items = inputs.stream().map(PaymentApprovalRequests::toItem).toList();
        long total = items.stream().mapToLong(ApprovalItem::amountMinor).sum();
        return new CreateApprovalRequest(purchaseRequestId, userId, total, items.get(0).currency(), items);
    }

    private static ApprovalItem toItem(ApprovalItemInput input) {
        Checkout checkout = input.checkout();
        List<SummaryItem> summary = checkout.lineItems().stream()
                .map(line -> new SummaryItem(line.item().title(), null, line.quantity(), line.item().price()))
                .toList();
        return new ApprovalItem(input.merchantId(), checkout.id(),
                PayCheckoutHash.toExternal(input.checkoutDigest()), checkout.merchantAuthorization(),
                new DisplaySnapshot(input.merchantName(), summary),
                checkout.amountOf("total"), checkout.currency());
    }

    /** 승인 요청 한 항목을 만드는 데 필요한 값. 최초 호출과 워커 재구성이 같은 입력을 쓴다. */
    public record ApprovalItemInput(
            String merchantId, String merchantName, String checkoutDigest, Checkout checkout) {
    }
}
