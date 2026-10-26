package com.copenhagen.core.payment;

import java.time.Clock;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.copenhagen.contract.internalapi.ApprovalStatus;
import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.PaymentRef;
import com.copenhagen.core.checkout.MerchantCheckoutClient;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CompleteResult;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.AwaitingApprovalCheckout;

/**
 * Merchant Complete Checkout 호출과 결과 반영(이슈 410, technical-flow.md §7). 승인이 확인된 뒤
 * {@link PaymentApprovalPollingWorker}가 한 행에 대해 한 번 부른다.
 */
@Service
public class PaymentCompletionService {

    private static final String IDEMPOTENCY_KEY_PREFIX = "checkout-complete-";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_READY_FOR_COMPLETE = "ready_for_complete";

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletionService.class);

    private final CartMerchants cartMerchants;
    private final Map<String, MerchantCheckoutClient> merchantCheckoutClients;
    private final PaymentCompletionRepository repository;
    private final Clock clock;

    public PaymentCompletionService(
            CartMerchants cartMerchants,
            Map<String, MerchantCheckoutClient> merchantCheckoutClients,
            PaymentCompletionRepository repository,
            Clock coreClock) {
        this.cartMerchants = cartMerchants;
        this.merchantCheckoutClients = merchantCheckoutClients;
        this.repository = repository;
        this.clock = coreClock;
    }

    public Outcome complete(AwaitingApprovalCheckout row, String credentialId, String paymentLabel) {
        String merchantId = cartMerchants.merchantId(row.merchantRefId());
        MerchantCheckoutClient client = merchantCheckoutClients.get(merchantId);
        if (client == null) {
            log.warn("Complete Checkout 대상 merchant 클라이언트 없음: merchantId={}, checkoutId={}",
                    merchantId, row.merchantCheckoutId());
            return Outcome.IN_PROGRESS;
        }

        CompleteResult result = client.complete(
                row.merchantCheckoutId(),
                credentialId,
                IDEMPOTENCY_KEY_PREFIX + row.merchantCheckoutId());

        return record(row, result, paymentLabel, true);
    }

    public Outcome synchronize(AwaitingApprovalCheckout row, String paymentLabel) {
        MerchantCheckoutClient client = merchantCheckoutClients.get(cartMerchants.merchantId(row.merchantRefId()));
        if (client == null) {
            return Outcome.IN_PROGRESS;
        }
        return record(row, client.current(row.merchantCheckoutId()), paymentLabel, false);
    }

    public Outcome recover(AwaitingApprovalCheckout row, PaymentApprovalStatusResponse status) {
        Outcome current = synchronize(row, status.paymentLabel());
        if (current != Outcome.IN_PROGRESS) {
            return current;
        }
        if (status.paymentStatus() == PaymentStatus.FAILED) {
            return record(row, new CompleteResult(row.merchantCheckoutId(), STATUS_READY_FOR_COMPLETE,
                    null, new PaymentRef(status.paymentId(), "FAILED", "PAYMENT_FAILED")),
                    status.paymentLabel(), true);
        }
        if (status.approvalStatus() == ApprovalStatus.APPROVED && status.credentialId() != null
                && !status.credentialId().isBlank()) {
            return complete(row, status.credentialId(), status.paymentLabel());
        }
        return Outcome.IN_PROGRESS;
    }

    private Outcome record(AwaitingApprovalCheckout row, CompleteResult result, String paymentLabel,
            boolean completionAttempted) {
        if (result == null || !row.merchantCheckoutId().equals(result.id())) {
            throw new IllegalStateException("Invalid Merchant checkout response");
        }
        if (STATUS_COMPLETED.equals(result.status())) {
            if (result.order() == null || result.order().id() == null || result.payment() == null
                    || result.payment().paymentId() == null || !"SUCCESS".equals(result.payment().status())) {
                throw new IllegalStateException("Incomplete Merchant completion response");
            }
            repository.recordCompleted(
                    row.checkoutSnapshotId(),
                    row.userId(),
                    row.paymentApprovalId(),
                    result.order().id(),
                    result.payment().paymentId(),
                    row.totalAmountMinor(),
                    row.currency(),
                    paymentLabel,
                    clock.instant());
            return Outcome.COMPLETED;
        }
        if ("expired".equals(result.status()) || "canceled".equals(result.status())) {
            return Outcome.EXPIRED;
        }
        if (STATUS_READY_FOR_COMPLETE.equals(result.status()) && (completionAttempted
                || result.payment() != null && "FAILED".equals(result.payment().status()))) {
            repository.recordFailed(
                    row.checkoutSnapshotId(),
                    row.paymentApprovalId(),
                    result.payment() == null ? null : result.payment().paymentId(),
                    row.totalAmountMinor(),
                    row.currency(),
                    result.payment() == null ? null : result.payment().errorCode(),
                    clock.instant());
            return Outcome.FAILED;
        }
        // 아직 완료되지 않은 주문서는 다음 폴링 또는 Webhook에서 다시 확인한다.
        return Outcome.IN_PROGRESS;
    }

    public enum Outcome {
        COMPLETED,
        FAILED,
        IN_PROGRESS,
        EXPIRED
    }
}
